package app.speaker.dictate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Continuous, live dictation. One tap starts a session; it keeps listening through pauses of any length
 * and streams text as you speak, until the user taps again (or the text field loses focus, handled by
 * BubbleService). A session never ends by itself on silence or errors.
 *
 * Built-in recognizer: uses partial results (live words) and, on Android 13+, a segmented session so it
 * doesn't stop at pauses. If the recognizer still ends an utterance, it's restarted with its beep muted.
 * API providers: phrase by phrase (text appears after each pause). Callbacks run on the main thread.
 */
class DictationEngine(private val ctx: Context, private val listener: Listener, private val owner: String) {

    interface Listener {
        fun onStateChanged(state: State)
        /**
         * Text of the current phrase. Called repeatedly with isFinal=false while words arrive (each call
         * replaces the previous one), then once with isFinal=true. [text] already includes a leading space
         * when it follows earlier text. [sessionText] = everything settled so far plus this phrase.
         */
        fun onText(text: String, isFinal: Boolean, sessionText: String)
        fun onError(message: String)
    }

    /** RECORDING = session active (listening). WORKING = stopping, finishing the last phrase. */
    enum class State { IDLE, RECORDING, WORKING }

    private val tag = "Speaker.engine[$owner]"
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(ctx)
    private val worker = Executors.newSingleThreadExecutor() // keeps phrases in order
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var recorder: SegmentingRecorder? = null
    private var session = 0
    private var sessionStartedAt = 0L
    private val sessionText = StringBuilder()
    @Volatile private var phrasesDone = 0
    @Volatile private var phraseErrors = 0
    @Volatile private var polishOff = false

    // Current (unsettled) phrase
    private var phrasePrefix: String? = null
    private var phraseText = ""

    // Built-in recognizer state
    private var recognizer: SpeechRecognizer? = null
    private var builtinActive = false
    private var builtinListening = false
    private var restartPending = false
    private var lastBuiltinEventAt = 0L
    private var builtinErrorStreak = 0
    private var builtinRestarts = 0
    private var ignoreLanguage = false
    private var segmentedSupported: Boolean? = null // for the recognizer currently in use; null = not known yet
    private var segmentedDefault: Boolean? = null   // remembered for the default (cloud) recognizer
    private var segmentedOnDevice: Boolean? = null  // remembered for the on-device recognizer

    private fun rememberSegmented(v: Boolean) {
        if (usingOnDevice) segmentedOnDevice = v else segmentedDefault = v
    }
    // Recognizer choice: 0 = on-device (phone language), 1 = on-device (en-US), 2 = default service.
    // On-device supports continuous (segmented) sessions with no beeps; default may restart and beep.
    private var recognizerStage = 0
    private var usingOnDevice = false
    private var gotResultFromRecognizer = false
    // Android 13+: we own the mic and stream audio to the recognizer (no mic on/off between utterances).
    private var micPipe: MicPipe? = null
    private var currentAudioPfd: android.os.ParcelFileDescriptor? = null
    private var audioSourceSupported: Boolean? = null // learned per engine lifetime
    private var usingAudioSource = false

    private fun stopMicPipe(why: String) {
        micPipe?.let {
            Log.i(tag, "Stopping app-owned mic stream ($why)")
            it.stop()
        }
        micPipe = null
        runCatching { currentAudioPfd?.close() }
        currentAudioPfd = null
    }
    private var lastRmsLogAt = 0L
    private var maxRmsSinceLog = -100f
    private var partialCount = 0

    var state = State.IDLE
        private set

    fun toggle() {
        Log.i(tag, "toggle() in state=$state")
        when (state) {
            State.IDLE -> start()
            State.RECORDING -> stop("user tap")
            State.WORKING -> Log.i(tag, "Tap ignored: still finishing the last phrase")
        }
    }

    /** Stops an active session (e.g. the text field lost focus). No-op otherwise. */
    fun stopIfActive(reason: String) {
        if (state == State.RECORDING) stop(reason) else Log.d(tag, "stopIfActive($reason) ignored in state=$state")
    }

    fun release() {
        Log.i(tag, "release() state=$state session=$session")
        recorder?.let { r -> Thread { runCatching { r.stop() } }.start() }
        recorder = null
        builtinActive = false
        main.removeCallbacksAndMessages(null)
        unmute()
        recognizer?.destroy()
        recognizer = null
        stopMicPipe("release")
        worker.shutdown()
        state = State.IDLE
    }

    private fun setState(s: State) {
        if (s == state) return
        Log.i(tag, "STATE $state -> $s (session $session)")
        state = s
        listener.onStateChanged(s)
    }

    // ---------------- session control ----------------

    private fun start() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "start() refused: RECORD_AUDIO not granted")
            listener.onError("Microphone permission not granted. Open Speaker to grant it.")
            return
        }
        val provider = SttProvider.from(prefs.sttProvider)
        if (provider.needsKey && prefs.apiKey(provider.vendor).isBlank()) {
            Log.w(tag, "start() refused: no API key for ${provider.id}")
            listener.onError("No API key set for ${provider.label}. Add it in Speaker settings.")
            return
        }
        session++
        sessionStartedAt = SystemClock.elapsedRealtime()
        sessionText.clear()
        phrasePrefix = null
        phraseText = ""
        phrasesDone = 0
        phraseErrors = 0
        polishOff = false
        builtinErrorStreak = 0
        builtinRestarts = 0
        partialCount = 0
        ignoreLanguage = false
        Log.i(tag, "SESSION $session START stt=${provider.id} model=${prefs.sttModel(provider)} " +
            "lang=${prefs.language.ifBlank { "auto" }} polish=${prefs.polishEnabled}" +
            (if (prefs.polishEnabled) " llm=${prefs.llmProvider}/${prefs.llmModel(LlmProvider.from(prefs.llmProvider))}" else "") +
            " autoType=${prefs.autoInsert} a11yConnected=${TypeAccessibilityService.instance != null} " +
            "fieldFocused=${TypeAccessibilityService.instance?.editorActive()} sdk=${Build.VERSION.SDK_INT}")
        try {
            if (provider == SttProvider.BUILTIN) {
                if (prefs.polishEnabled) Log.i(tag, "Polish is skipped for the built-in recognizer (live text can't be rewritten)")
                builtinActive = true
                listenOnce("session start", muteBeep = false)
            } else {
                val sid = session
                val r = SegmentingRecorder(ctx.cacheDir) { file, info ->
                    Log.i(tag, "Queueing phrase #${info.index} for transcription")
                    worker.execute { transcribePhrase(sid, file, info) }
                }
                r.start()
                recorder = r
            }
            setState(State.RECORDING)
            scheduleHeartbeat(session)
        } catch (e: Exception) {
            Log.e(tag, "SESSION $session could not start", e)
            builtinActive = false
            setState(State.IDLE)
            listener.onError("Could not start: ${e.message}")
        }
    }

    private fun stop(reason: String) {
        Log.i(tag, "SESSION $session STOP requested ($reason) after ${elapsed()}s, phrases=$phrasesDone")
        setState(State.WORKING)
        val r = recorder
        if (r != null) {
            recorder = null
            worker.execute {
                runCatching { r.stop() }.onFailure { e ->
                    Log.e(tag, "Recorder stop failed", e)
                    main.post { listener.onError("Recording error: ${e.message}") }
                }
                worker.execute { main.post { finishSession() } } // after the last phrase
            }
            return
        }
        builtinActive = false
        if (builtinListening) {
            Log.i(tag, "Built-in: stopListening() to get the final words (audioSource=$usingAudioSource)")
            if (usingAudioSource) micPipe?.detach() // end of audio stream = end of session
            recognizer?.stopListening()
            main.postDelayed({
                if (state == State.WORKING) {
                    Log.w(tag, "Built-in gave no final callback within 4s; finishing anyway")
                    finishAfterQueue()
                }
            }, 4000)
        } else {
            finishAfterQueue()
        }
    }

    private fun finishAfterQueue() = worker.execute { main.post { finishSession() } }

    private fun finishSession() {
        if (state == State.IDLE) return
        settleLivePhrase("session end")
        unmute()
        recognizer?.destroy() // a fresh recognizer per session avoids stale state
        recognizer = null
        stopMicPipe("session end")
        Log.i(tag, "SESSION $session FINISHED after ${elapsed()}s: phrases=$phrasesDone errors=$phraseErrors " +
            "builtinRestarts=$builtinRestarts partials=$partialCount segmented=$segmentedSupported chars=${sessionText.length}")
        setState(State.IDLE)
        if (sessionText.isEmpty() && phraseErrors == 0) listener.onError("No speech detected")
    }

    private fun elapsed() = (SystemClock.elapsedRealtime() - sessionStartedAt) / 1000

    /** Logs every 10 s while a session is active, and revives a dead recorder. */
    private fun scheduleHeartbeat(sid: Int) {
        main.postDelayed({
            if (sid != session || state == State.IDLE) return@postDelayed
            val r = recorder
            if (state == State.RECORDING && r != null && !r.isAlive) {
                Log.w(tag, "WATCHDOG: recorder thread died; starting a new one")
                runCatching { r.stop() }
                recorder = runCatching {
                    SegmentingRecorder(ctx.cacheDir) { file, info ->
                        worker.execute { transcribePhrase(sid, file, info) }
                    }.also { it.start() }
                }.getOrElse { e ->
                    Log.e(tag, "Could not restart recorder; will retry next heartbeat", e)
                    r
                }
            }
            Log.i(tag, "HEARTBEAT session=$sid state=$state t=${elapsed()}s phrases=$phrasesDone errors=$phraseErrors " +
                (if (recorder != null) "recorder=running" else "builtin active=$builtinActive listening=$builtinListening " +
                    "restartPending=$restartPending restarts=$builtinRestarts errStreak=$builtinErrorStreak " +
                    "segmented=$segmentedSupported partials=$partialCount") +
                " fieldFocused=${TypeAccessibilityService.instance?.editorActive()}")
            scheduleHeartbeat(sid)
        }, 10_000)
    }

    // ---------------- phrase text plumbing ----------------

    private fun spaceBefore(t: String) =
        if (sessionText.isNotEmpty() && !sessionText.last().isWhitespace() && t.first() !in ".,!?;:)") " " else ""

    /** Live words for the phrase in progress. */
    private fun emitPartial(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val prefix = phrasePrefix ?: spaceBefore(t).also { phrasePrefix = it }
        val full = prefix + t
        if (full == phraseText) return
        phraseText = full
        partialCount++
        Log.d(tag, "Live: \"${full.takeLast(80)}\" (${full.length} chars)")
        listener.onText(full, false, sessionText.toString() + full)
    }

    /** Settled text for the phrase in progress (replaces whatever live text was shown). */
    private fun emitFinal(raw: String, what: String) {
        val t = raw.trim()
        if (t.isEmpty()) {
            if (phraseText.isNotEmpty()) {
                Log.i(tag, "Final for $what is empty; keeping the live text that was already typed")
                settleLivePhrase(what)
            }
            return
        }
        val full = (phrasePrefix ?: spaceBefore(t)) + t
        sessionText.append(full)
        phrasesDone++
        phrasePrefix = null
        phraseText = ""
        Log.i(tag, "Final ($what): \"${full.take(200)}\" (session ${sessionText.length} chars)")
        listener.onText(full, true, sessionText.toString())
    }

    /** Treats the live text currently on screen as final (used when the recognizer gives no final). */
    private fun settleLivePhrase(why: String) {
        if (phraseText.isEmpty()) return
        val full = phraseText
        sessionText.append(full)
        phrasesDone++
        phrasePrefix = null
        phraseText = ""
        Log.i(tag, "Settled live text as final ($why): ${full.length} chars")
        listener.onText(full, true, sessionText.toString())
    }

    // ---------------- API providers (recorded phrases) ----------------

    /** Runs on [worker]. */
    private fun transcribePhrase(sid: Int, file: File, info: SegmentingRecorder.Segment) {
        val provider = SttProvider.from(prefs.sttProvider)
        try {
            val t0 = SystemClock.elapsedRealtime()
            Log.i(tag, "Phrase #${info.index}: sending ${file.length()} bytes (${info.durationMs} ms) to ${provider.id}/${prefs.sttModel(provider)}")
            val raw = Transcriber(prefs).transcribe(file).trim()
            Log.i(tag, "Phrase #${info.index}: ${provider.id} returned ${raw.length} chars in " +
                "${SystemClock.elapsedRealtime() - t0} ms: \"${raw.take(200)}\"")
            if (raw.lowercase() in HALLUCINATIONS && info.speechMs < 1500) {
                Log.i(tag, "Phrase #${info.index}: dropped likely hallucination \"$raw\"")
                return
            }
            val (text, note) = maybePolish(raw)
            main.post {
                if (sid != session) return@post
                emitFinal(text, "phrase #${info.index}")
                note?.let { listener.onError(it) }
            }
        } catch (e: Exception) {
            phraseErrors++
            Log.e(tag, "Phrase #${info.index} FAILED (${provider.id}, ${info.durationMs} ms, ${file.length()} bytes)", e)
            val authProblem = e is IOException && (e.message?.startsWith("HTTP 401") == true || e.message?.startsWith("HTTP 403") == true)
            main.post {
                listener.onError("Transcription failed (${provider.label}): ${e.message}")
                if (authProblem && state == State.RECORDING && sid == session) {
                    Log.w(tag, "Stopping session: API key rejected")
                    listener.onError("API key rejected by ${provider.label}. Stopping.")
                    stop("auth error")
                }
            }
        } finally {
            file.delete()
        }
    }

    /** Runs on [worker]. Returns the text to use and an optional note for the user. */
    private fun maybePolish(raw: String): Pair<String, String?> {
        if (raw.isEmpty() || !prefs.polishEnabled || polishOff) return raw to null
        val llm = LlmProvider.from(prefs.llmProvider)
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val polished = Transcriber(prefs).polish(raw).trim().ifEmpty { raw }
            Log.i(tag, "Polished with ${llm.id} in ${SystemClock.elapsedRealtime() - t0} ms: \"${polished.take(200)}\"")
            polished to null
        } catch (e: Exception) {
            Log.e(tag, "Polish failed (${llm.id}); using raw text", e)
            val hard = e is IOException && Regex("^HTTP 40[0-3]").containsMatchIn(e.message.orEmpty())
            if (hard) {
                polishOff = true
                Log.w(tag, "Polish turned off for the rest of this session (provider refused: ${e.message})")
                raw to "Polish off for this session: ${llm.label} said ${e.message}"
            } else {
                raw to "Polish failed (${llm.label}): ${e.message}. Used raw text."
            }
        }
    }

    // ---------------- Android built-in recognizer ----------------

    private fun createRecognizer(): SpeechRecognizer {
        val onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)
        usingOnDevice = onDeviceAvailable && recognizerStage < 2
        gotResultFromRecognizer = false
        segmentedSupported = if (usingOnDevice) segmentedOnDevice else segmentedDefault
        Log.i(tag, "Built-in: creating ${if (usingOnDevice) "ON-DEVICE" else "DEFAULT"} SpeechRecognizer " +
            "(onDeviceAvailable=$onDeviceAvailable stage=$recognizerStage segmentedKnown=$segmentedSupported)")
        val sr = if (usingOnDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        return sr.also { it.setRecognitionListener(builtinListener) }
    }

    /** Language tag for this attempt (on-device needs an explicit, downloaded language). */
    private fun languageFor(): String? = when {
        ignoreLanguage -> null
        prefs.language.isNotBlank() -> prefs.language
        usingOnDevice && recognizerStage == 0 -> java.util.Locale.getDefault().toLanguageTag()
        usingOnDevice -> "en-US"
        else -> null
    }

    /** On-device recognizer failed before producing anything: try the next option. */
    private fun fallBackFromOnDevice(error: Int, name: String) {
        val langIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            languageFor()?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        recognizerStage = if (recognizerStage == 0 && prefs.language.isBlank()) 1 else 2
        if ((error == 12 || error == 13) && Build.VERSION.SDK_INT >= 33) {
            Log.i(tag, "Built-in: asking Android to download the on-device language model for next time")
            runCatching { recognizer?.triggerModelDownload(langIntent) }.onFailure { Log.w(tag, "triggerModelDownload failed: $it") }
        }
        Log.w(tag, "Built-in: on-device recognizer failed with $name before any result; moving to stage $recognizerStage " +
            "(${if (recognizerStage == 1) "on-device en-US" else "default recognizer"})")
        restartBuiltin(150, "on-device fallback ($name)", recreate = true)
    }

    private fun listenOnce(why: String, muteBeep: Boolean) {
        restartPending = false
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            throw IllegalStateException("No speech recognition service on this device. Choose an API provider in settings.")
        }
        val sr = recognizer ?: createRecognizer().also { recognizer = it }
        usingAudioSource = Build.VERSION.SDK_INT >= 33 && audioSourceSupported != false
        val segmented = Build.VERSION.SDK_INT >= 33 && (usingAudioSource || segmentedSupported != false)
        var audioPfd: android.os.ParcelFileDescriptor? = null
        if (usingAudioSource) {
            val pipe = micPipe ?: MicPipe().also { it.start(); micPipe = it }
            audioPfd = pipe.attachNew()
        } else if (micPipe != null) {
            stopMicPipe("switching to recognizer-owned mic")
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (audioPfd != null && Build.VERSION.SDK_INT >= 33) {
                // Our own continuous audio stream; the session lasts until we close the stream.
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioPfd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicPipe.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            } else if (segmented) {
                // Keep one session open through pauses; results arrive per segment.
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30 * 60_000L)
            } else {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            }
            languageFor()?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        // Restart loop (recognizer ends at pauses): keep beep-carrying streams muted for the session.
        if (muteBeep && segmentedSupported != true) muteSession()
        builtinListening = true
        lastBuiltinEventAt = SystemClock.elapsedRealtime()
        Log.i(tag, "Built-in: startListening ($why) restart #$builtinRestarts onDevice=$usingOnDevice " +
            "audioSource=$usingAudioSource lang=${languageFor() ?: "default"} segmented=$segmented muted=${mutedStreams.isNotEmpty()}")
        sr.startListening(intent)
        // startListening hands the intent over asynchronously, so close our copy of the previous fd only now.
        runCatching { currentAudioPfd?.close() }
        currentAudioPfd = audioPfd
        scheduleWatchdog(session)
    }

    private fun restartBuiltin(delayMs: Long, why: String, recreate: Boolean = false) {
        if (restartPending) { Log.d(tag, "Built-in: restart already pending, skip ($why)"); return }
        restartPending = true
        builtinRestarts++
        if (recreate) {
            Log.i(tag, "Built-in: destroying recognizer before restart ($why)")
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        Log.i(tag, "Built-in: restart in ${delayMs}ms ($why)")
        main.postDelayed({
            restartPending = false
            if (builtinActive && state == State.RECORDING) {
                runCatching { listenOnce(why, muteBeep = true) }.onFailure { e ->
                    Log.e(tag, "Built-in: restart failed, will retry", e)
                    builtinListening = false
                    restartBuiltin(2000, "retry after failed restart", recreate = true)
                }
            } else {
                Log.i(tag, "Built-in: restart skipped (active=$builtinActive state=$state)")
            }
        }, delayMs)
    }

    private fun scheduleWatchdog(sid: Int) {
        main.postDelayed({
            if (sid != session || !builtinActive || state != State.RECORDING) return@postDelayed
            val idle = SystemClock.elapsedRealtime() - lastBuiltinEventAt
            when {
                !builtinListening && !restartPending -> {
                    Log.w(tag, "WATCHDOG: built-in not listening and no restart pending; restarting")
                    restartBuiltin(0, "watchdog: not listening", recreate = true)
                }
                builtinListening && idle > 90_000 -> {
                    Log.w(tag, "WATCHDOG: no recognizer callback for ${idle / 1000}s; recreating")
                    builtinListening = false
                    restartBuiltin(0, "watchdog: stuck", recreate = true)
                }
                else -> scheduleWatchdog(sid)
            }
        }, 3000)
    }

    // Mute the recognizer's restart beeps for the rest of the session (only streams that weren't already
    // muted). Saved to prefs so a crash can't leave the phone muted: SpeakerApp restores them on start.
    private val mutedStreams = mutableListOf<Int>()

    private fun muteSession() {
        if (mutedStreams.isNotEmpty()) return
        for (s in BEEP_STREAMS) {
            runCatching {
                if (!audio.isStreamMute(s)) {
                    audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams += s
                }
            }.onFailure { Log.d(tag, "Could not mute stream $s: $it") }
        }
        prefs.mutedStreams = mutedStreams.joinToString(",")
        Log.i(tag, "Beep mute ON for the session, streams $mutedStreams (3=music 1=system 5=notification 10=accessibility)")
    }

    private fun unmute() {
        if (mutedStreams.isEmpty()) return
        for (s in mutedStreams) runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
        Log.i(tag, "Beep mute OFF, streams $mutedStreams")
        mutedStreams.clear()
        prefs.mutedStreams = ""
    }

    private val builtinListener = object : RecognitionListener {
        private fun event() { lastBuiltinEventAt = SystemClock.elapsedRealtime() }

        override fun onReadyForSpeech(params: Bundle?) { event(); Log.i(tag, "Built-in: onReadyForSpeech") }
        override fun onBeginningOfSpeech() { event(); Log.i(tag, "Built-in: onBeginningOfSpeech") }
        override fun onEndOfSpeech() {
            event()
            Log.i(tag, "Built-in: onEndOfSpeech (onDevice=$usingOnDevice segmented=$segmentedSupported)")
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) { event(); Log.i(tag, "Built-in: onEvent type=$eventType") }

        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB > maxRmsSinceLog) maxRmsSinceLog = rmsdB
            val now = SystemClock.elapsedRealtime()
            if (now - lastRmsLogAt > 5000) {
                Log.d(tag, "Built-in: mic level max $maxRmsSinceLog dB over last 5s")
                lastRmsLogAt = now
                maxRmsSinceLog = -100f
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            event()
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                gotResultFromRecognizer = true
                emitPartial(text)
            }
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            event()
            if (segmentedSupported != true) Log.i(tag, "Built-in: continuous (segmented) session SUPPORTED by the ${if (usingOnDevice) "on-device" else "default"} recognizer")
            segmentedSupported = true
            rememberSegmented(true)
            gotResultFromRecognizer = true
            builtinErrorStreak = 0
            val text = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            Log.i(tag, "Built-in: onSegmentResults \"${text.take(200)}\"")
            emitFinal(text, "segment")
        }

        override fun onEndOfSegmentedSession() {
            event()
            builtinListening = false
            Log.i(tag, "Built-in: onEndOfSegmentedSession (active=$builtinActive)")
            settleLivePhrase("segmented session ended")
            if (builtinActive) restartBuiltin(100, "segmented session ended") else finishAfterQueue()
        }

        override fun onResults(results: Bundle?) {
            event()
            builtinListening = false
            builtinErrorStreak = 0
            gotResultFromRecognizer = true
            if (segmentedSupported == null && Build.VERSION.SDK_INT >= 33) {
                segmentedSupported = false
                rememberSegmented(false)
                Log.i(tag, "Built-in: ${if (usingOnDevice) "on-device" else "default"} recognizer ignored the continuous session " +
                    "(got onResults); using restart loop with muted beeps")
            }
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val text = all.firstOrNull().orEmpty()
            Log.i(tag, "Built-in: onResults ${all.size} alternative(s), best=\"${text.take(200)}\" (active=$builtinActive)")
            emitFinal(text, "utterance")
            if (builtinActive) restartBuiltin(50, "after result") else finishAfterQueue()
        }

        override fun onError(error: Int) {
            event()
            builtinListening = false
            val name = errorName(error)
            Log.i(tag, "Built-in: onError $error ($name) active=$builtinActive streak=$builtinErrorStreak segmented=$segmentedSupported")
            if (!builtinActive) { finishAfterQueue(); return }
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (usingAudioSource && !gotResultFromRecognizer && !silence && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                audioSourceSupported = false
                Log.w(tag, "Built-in: recognizer rejected the app's audio stream ($name); falling back to recognizer-owned mic")
                settleLivePhrase("audio-source error $name")
                stopMicPipe("audio source not supported")
                restartBuiltin(150, "audio-source fallback ($name)", recreate = true)
                return
            }
            if (usingOnDevice && !gotResultFromRecognizer && !silence && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                settleLivePhrase("on-device error $name")
                fallBackFromOnDevice(error, name)
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    builtinErrorStreak = 0
                    settleLivePhrase("silence ($name)")
                    restartBuiltin(50, "silence ($name)")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    builtinActive = false
                    listener.onError("Microphone permission missing")
                    finishAfterQueue()
                }
                12, 13 -> { // language not supported / unavailable
                    ignoreLanguage = true
                    listener.onError("Language '${prefs.language}' not available in the built-in recognizer; using the phone's default")
                    restartBuiltin(300, name, recreate = true)
                }
                else -> {
                    settleLivePhrase("error $name")
                    if (segmentedSupported == null && Build.VERSION.SDK_INT >= 33) {
                        segmentedSupported = false
                        rememberSegmented(false)
                        Log.w(tag, "Built-in: error on first segmented attempt; retrying without segmented session")
                        restartBuiltin(200, "segmented not supported ($name)", recreate = true)
                        return
                    }
                    builtinErrorStreak++
                    val delay = minOf(400L * builtinErrorStreak, 5000L)
                    if (builtinErrorStreak == 3) listener.onError("Speech recognizer is having trouble ($name). Still listening…")
                    restartBuiltin(delay, name, recreate = error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY || builtinErrorStreak > 2)
                }
            }
        }
    }

    private fun errorName(code: Int) = when (code) {
        1 -> "NETWORK_TIMEOUT"; 2 -> "NETWORK"; 3 -> "AUDIO"; 4 -> "SERVER"; 5 -> "CLIENT"
        6 -> "SPEECH_TIMEOUT"; 7 -> "NO_MATCH"; 8 -> "RECOGNIZER_BUSY"; 9 -> "INSUFFICIENT_PERMISSIONS"
        10 -> "TOO_MANY_REQUESTS"; 11 -> "SERVER_DISCONNECTED"; 12 -> "LANGUAGE_NOT_SUPPORTED"
        13 -> "LANGUAGE_UNAVAILABLE"; 14 -> "CANNOT_CHECK_SUPPORT"; 15 -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
        else -> "UNKNOWN"
    }

    companion object {
        val BEEP_STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ACCESSIBILITY,
        )

        /** Undo a mute left behind by a crash (called at app start). */
        fun restoreLeftoverMute(ctx: Context) {
            val prefs = Prefs(ctx)
            val leftover = prefs.mutedStreams.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (leftover.isEmpty()) return
            val audio = ctx.getSystemService(AudioManager::class.java)
            leftover.forEach { s -> runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } }
            prefs.mutedStreams = ""
            Log.w("Speaker.engine", "Restored sound streams $leftover left muted by a previous run")
        }

        // Whisper-family models invent these from near-silence.
        val HALLUCINATIONS = setOf(
            "thank you.", "thank you", "thanks for watching!", "thanks for watching.", "you", "bye.", ".",
        )
    }
}
