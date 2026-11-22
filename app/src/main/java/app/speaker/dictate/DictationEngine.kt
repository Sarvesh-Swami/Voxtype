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
        /**
         * AI rewrite mode ([draftMode]): nothing is typed. Words of the phrase in progress arrive with
         * isFinal=false (each call replaces the previous one), then the settled phrase once with
         * isFinal=true. [phrase] is trimmed; the listener keeps the draft and sends it with [rewrite].
         */
        fun onDraft(phrase: String, isFinal: Boolean) {}
        /** The user started (true) or stopped (false) speaking, from the mic level. Drives the bubble animation. */
        fun onVoice(speaking: Boolean) {}
    }

    // Last value sent to onVoice, so it's only called on changes. Main thread only.
    private var voiceOn = false
    private fun voice(on: Boolean) {
        if (on == voiceOn) return
        voiceOn = on
        listener.onVoice(on)
    }

    /** Result of [rewrite]: [text] is the AI's version when [ok], else the input; [why] says what failed. */
    data class Rewrite(val text: String, val ok: Boolean, val why: String?)

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

    /**
     * AI rewrite mode for this session (fixed when it starts): spoken words are NEVER typed. They go
     * to the listener as a draft ([Listener.onDraft]); the user edits it and sends the whole text to
     * the AI in one call ([rewrite]), so the model sees the full context.
     */
    var draftMode = false
        private set
    private val aiSeq = java.util.concurrent.atomic.AtomicInteger(0)

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

    // Recognizer liveness: a working recognizer reports the mic level (onRmsChanged) many times a
    // second, pauses included. If that stops while we think it's listening, it died silently.
    private var lastRmsAt = 0L
    private var rmsSeenThisListen = false
    private var micSilencedNotified = false

    // After release() nothing may run: late callbacks from worker threads or the mic thread are dropped.
    @Volatile private var released = false

    /** Runs [block] on the phrase worker (in order), unless the engine was released. */
    private fun onWorker(block: () -> Unit) {
        if (released) return
        runCatching { worker.execute(block) }.onFailure { Log.w(tag, "Worker task dropped: $it") }
    }

    /** Posts to the main thread, unless the engine was released. */
    private fun post(block: () -> Unit) {
        main.post { if (!released) block() }
    }

    /** Speech started/stopped, from the API recorder's thread. */
    private fun recorderSpeech(on: Boolean) = post { if (state == State.RECORDING || !on) voice(on) }

    private val micEvents = object : MicPipe.Events {
        override fun onPipeClosedByRecognizer() = post {
            if (!builtinActive || state != State.RECORDING || !usingAudioSource) return@post
            Log.w(tag, "Recognizer closed the audio stream without telling us; restarting it now")
            builtinListening = false
            settleLivePhrase("recognizer closed the audio stream")
            restartBuiltin(100, "audio stream closed by recognizer", recreate = true)
        }

        override fun onMicSilenced(silenced: Boolean) = post {
            if (state == State.IDLE) return@post
            if (silenced && !micSilencedNotified) {
                micSilencedNotified = true
                listener.onError("Another app is using the microphone. Speaker will hear you again when it lets go.")
            }
            if (!silenced) micSilencedNotified = false
        }

        override fun onMicFailed(error: Exception) = post {
            if (state == State.IDLE) return@post
            Log.e(tag, "Microphone failed; ending the session", error)
            builtinActive = false
            listener.onError("The microphone stopped working (${error.message}). Tap to try again.")
            stopMicPipe("mic failed")
            finishAfterQueue()
        }
    }

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
        released = true
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
        if (s != State.RECORDING) voice(false)
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
        micSilencedNotified = false
        // Recorded phrases are deleted after upload; this clears any left behind by a crash or kill.
        if (state == State.IDLE) runCatching {
            ctx.cacheDir.listFiles { f -> f.name.startsWith("phrase-") && f.name.endsWith(".wav") }?.forEach { it.delete() }
        }
        sessionText.clear()
        phrasePrefix = null
        phraseText = ""
        phrasesDone = 0
        phraseErrors = 0
        draftMode = prefs.polishEnabled
        builtinErrorStreak = 0
        builtinRestarts = 0
        partialCount = 0
        ignoreLanguage = false
        Log.i(tag, "SESSION $session START stt=${provider.id} model=${prefs.sttModel(provider)} " +
            "lang=${prefs.language.ifBlank { "auto" }} polish=${prefs.polishEnabled}" +
            (if (prefs.polishEnabled) " llm=${prefs.llmProvider}/${prefs.llmModel(LlmProvider.from(prefs.llmProvider))}" else "") +
            " autoType=${prefs.autoInsert} a11yConnected=${TypeAccessibilityService.instance != null} " +
            "fieldFocused=${TypeAccessibilityService.instance?.editorActive()} sdk=${Build.VERSION.SDK_INT}")
        if (draftMode) Log.i(tag, "AI rewrite ON: nothing is typed while you speak; the words go to the draft card " +
            "and the whole text is rewritten in one call when you tap Send")
        try {
            if (provider == SttProvider.BUILTIN) {
                builtinActive = true
                listenOnce("session start", muteBeep = false)
            } else {
                val sid = session
                val r = SegmentingRecorder(ctx.cacheDir, onSpeech = ::recorderSpeech) { file, info ->
                    Log.i(tag, "Queueing phrase #${info.index} for transcription")
                    onWorker { transcribePhrase(sid, file, info) }
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
            onWorker {
                runCatching { r.stop() }.onFailure { e ->
                    Log.e(tag, "Recorder stop failed", e)
                    post { listener.onError("Recording error: ${e.message}") }
                }
                onWorker { post { finishSession() } } // after the last phrase
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

    private fun finishAfterQueue() = onWorker { post { finishSession() } }

    private fun finishSession() {
        if (state == State.IDLE) return
        settleLivePhrase("session end")
        unmute()
        recognizer?.destroy() // a fresh recognizer per session avoids stale state
        recognizer = null
        stopMicPipe("session end")
        Log.i(tag, "SESSION $session FINISHED after ${elapsed()}s: phrases=$phrasesDone errors=$phraseErrors " +
            "builtinRestarts=$builtinRestarts partials=$partialCount segmented=$segmentedSupported chars=${sessionText.length}" +
            (if (draftMode) " (AI draft: nothing typed, waiting for Send)" else ""))
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
                    SegmentingRecorder(ctx.cacheDir, onSpeech = ::recorderSpeech) { file, info ->
                        onWorker { transcribePhrase(sid, file, info) }
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
        if (draftMode) {
            // Shown in the draft card only: the spoken words never reach the text field.
            Log.d(tag, "Live (AI draft, not typed): ${spoken(t.takeLast(80))} (${t.length} chars)")
            listener.onDraft(t, false)
            return
        }
        Log.d(tag, "Live: ${spoken(full.takeLast(80))} (${full.length} chars)")
        listener.onText(full, false, sessionText.toString() + full)
    }

    /** Settled text for the phrase in progress (replaces whatever live text was shown). */
    private fun emitFinal(raw: String, what: String) {
        val t = raw.trim()
        if (t.isEmpty()) {
            if (phraseText.isNotEmpty()) {
                Log.i(tag, "Final for $what is empty; using the live words instead")
                settleLivePhrase(what)
            }
            return
        }
        val full = (phrasePrefix ?: spaceBefore(t)) + t
        sessionText.append(full)
        phrasesDone++
        phrasePrefix = null
        phraseText = ""
        if (draftMode) {
            Log.i(tag, "Final ($what) added to the AI draft, not typed: ${spoken(t)} (draft ${sessionText.length} chars)")
            listener.onDraft(t, true)
            return
        }
        Log.i(tag, "Final ($what): ${spoken(full)} (session ${sessionText.length} chars)")
        listener.onText(full, true, sessionText.toString())
    }

    /** Treats the live text currently on screen as final (used when the recognizer gives no final). */
    private fun settleLivePhrase(why: String) {
        if (phraseText.isEmpty()) return
        if (draftMode) {
            val raw = phraseText.removePrefix(phrasePrefix.orEmpty()).trim()
            sessionText.append(phraseText)
            phrasesDone++
            phrasePrefix = null
            phraseText = ""
            Log.i(tag, "Settled live words into the AI draft ($why): ${raw.length} chars")
            if (raw.isNotEmpty()) listener.onDraft(raw, true)
            return
        }
        val full = phraseText
        sessionText.append(full)
        phrasesDone++
        phrasePrefix = null
        phraseText = ""
        Log.i(tag, "Settled live text as final ($why): ${full.length} chars")
        listener.onText(full, true, sessionText.toString())
    }

    /**
     * AI rewrite: sends the whole draft (as the user edited it) to the LLM in one call, so the model
     * sees the full context. [done] runs on the main thread. Works whether or not a session is active.
     */
    fun rewrite(text: String, done: (Rewrite) -> Unit) {
        val raw = text.trim()
        val n = aiSeq.incrementAndGet()
        val queuedAt = SystemClock.elapsedRealtime()
        Log.i(tag, "AI REWRITE #$n queued: ${raw.length} chars")
        if (released) return
        onWorker {
            val result = polishAll(raw, "#$n")
            post {
                Log.i(tag, "AI REWRITE #$n done after ${SystemClock.elapsedRealtime() - queuedAt} ms total " +
                    "(${if (result.ok) "rewritten" else "FAILED: ${result.why}"})")
                done(result)
            }
        }
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
                "${SystemClock.elapsedRealtime() - t0} ms: ${spoken(raw)}")
            if (raw.lowercase() in HALLUCINATIONS && info.speechMs < 1500) {
                Log.i(tag, "Phrase #${info.index}: dropped likely hallucination ${spoken(raw)}")
                return
            }
            post {
                if (sid != session) return@post
                emitFinal(raw, "phrase #${info.index}")
            }
        } catch (e: Exception) {
            phraseErrors++
            Log.e(tag, "Phrase #${info.index} FAILED (${provider.id}, ${info.durationMs} ms, ${file.length()} bytes)", e)
            val authProblem = e is IOException && (e.message?.startsWith("HTTP 401") == true || e.message?.startsWith("HTTP 403") == true)
            post {
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

    /** Runs on [worker]: one LLM call for the whole draft. Never throws. */
    private fun polishAll(raw: String, label: String): Rewrite {
        if (raw.isEmpty()) return Rewrite(raw, false, "nothing to rewrite")
        val llm = LlmProvider.from(prefs.llmProvider)
        val model = prefs.llmModel(llm)
        val t0 = SystemClock.elapsedRealtime()
        Log.i(tag, "AI REWRITE $label: sending ${raw.length} chars ${spoken(raw)} to ${llm.id}/$model, " +
            "prompt ${prefs.polishPrompt.length} chars, timeout ${Transcriber.POLISH_TIMEOUT_MS / 1000} s")
        return try {
            val reply = Transcriber(prefs).polish(raw)
            val ms = SystemClock.elapsedRealtime() - t0
            val cleaned = cleanRewrite(reply, raw)
            if (cleaned.isEmpty()) {
                Log.w(tag, "AI REWRITE $label FAILED cause=EMPTY_REPLY: ${llm.id}/$model answered with no text in $ms ms " +
                    "(reply was ${reply.length} chars before cleanup)")
                return Rewrite(raw, false, "the AI returned nothing")
            }
            Log.i(tag, "AI REWRITE $label OK in $ms ms: ${raw.length} -> ${cleaned.length} chars ${spoken(cleaned)}")
            if (cleaned.length > raw.length * 3 && cleaned.length > 60) {
                Log.w(tag, "AI REWRITE $label: reply is ${cleaned.length / raw.length.coerceAtLeast(1)}x longer than the " +
                    "dictation. The model may be answering instead of rewriting; check the session prompt")
            }
            if (ms > 5000) Log.w(tag, "AI REWRITE $label SLOW: $ms ms. A faster model (e.g. Groq, a mini/flash/haiku model) cuts the delay")
            Rewrite(cleaned, true, null)
        } catch (e: Exception) {
            val ms = SystemClock.elapsedRealtime() - t0
            val (cause, hint) = rewriteFailure(e)
            Log.e(tag, "AI REWRITE $label FAILED cause=$cause after $ms ms (${llm.id}/$model): ${e.javaClass.simpleName}: ${e.message}", e)
            Rewrite(raw, false, hint)
        }
    }

    /** Strips wrappers models add despite instructions: surrounding quotes, "Here is…:" lead-ins. */
    private fun cleanRewrite(reply: String, raw: String): String {
        var t = reply.trim()
        val lead = Regex("^(here('s| is) (the )?(rewritten|corrected|cleaned|revised)[^:\\n]{0,40}:)\\s*", RegexOption.IGNORE_CASE)
        lead.find(t)?.let {
            Log.w(tag, "AI rewrite reply started with a lead-in (\"${it.value.trim()}\"); removed it")
            t = t.substring(it.range.last + 1).trim()
        }
        val quotes = listOf("\"" to "\"", "“" to "”")
        for ((open, close) in quotes) {
            if (t.length >= 2 && t.startsWith(open) && t.endsWith(close) && !raw.trim().startsWith(open)) {
                t = t.substring(open.length, t.length - close.length).trim()
                Log.i(tag, "AI rewrite reply was wrapped in quotes; removed them")
                break
            }
        }
        return t
    }

    /** Readable cause for the log, and a hint for the user. */
    private fun rewriteFailure(e: Exception): Pair<String, String> {
        val llm = LlmProvider.from(prefs.llmProvider).label
        val msg = e.message.orEmpty()
        val code = Regex("^HTTP (\\d{3})").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            e is java.net.SocketTimeoutException ->
                Pair("TIMEOUT", "$llm didn't answer in ${Transcriber.POLISH_TIMEOUT_MS / 1000} s")
            e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
                Pair("NO_NETWORK", "can't reach $llm (check internet)")
            e is javax.net.ssl.SSLException ->
                Pair("TLS", "secure connection to $llm failed")
            code == 401 || code == 403 ->
                Pair("KEY_REJECTED_$code", "$llm rejected the API key")
            code == 404 ->
                Pair("MODEL_NOT_FOUND", "$llm doesn't know model \"${prefs.llmModel(LlmProvider.from(prefs.llmProvider))}\"")
            code == 400 || code == 422 ->
                Pair("BAD_REQUEST_$code", "$llm refused the request: ${msg.removePrefix("HTTP $code: ").take(120)}")
            code == 402 ->
                Pair("NO_CREDIT", "$llm says the account has no credit")
            code == 429 ->
                Pair("RATE_LIMIT", "$llm rate limit or quota reached")
            code != null && code >= 500 ->
                Pair("PROVIDER_ERROR_$code", "$llm had a server error ($code)")
            e is IllegalStateException ->
                Pair("NOT_CONFIGURED", msg) // no key / no model
            e is org.json.JSONException ->
                Pair("BAD_RESPONSE", "unexpected reply from $llm")
            e is IOException ->
                Pair("NETWORK_IO", "connection to $llm failed (${e.javaClass.simpleName})")
            else -> Pair("UNEXPECTED", "${e.javaClass.simpleName}: ${msg.take(120)}")
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
            val pipe = micPipe ?: MicPipe(micEvents).also { it.start(); micPipe = it }
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
        rmsSeenThisListen = false
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

    // One watchdog loop per engine (re-arming replaces it; restarts used to stack extra loops).
    private val watchdog = Runnable { watchdogTick() }

    @Suppress("UNUSED_PARAMETER")
    private fun scheduleWatchdog(sid: Int) {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    private fun watchdogTick() {
        if (released || !builtinActive || state != State.RECORDING) return
        val now = SystemClock.elapsedRealtime()
        val idle = now - lastBuiltinEventAt
        val rmsSilentFor = now - lastRmsAt
        when {
            !builtinListening && !restartPending -> {
                Log.w(tag, "WATCHDOG: built-in not listening and no restart pending; restarting")
                restartBuiltin(0, "watchdog: not listening", recreate = true)
            }
            // A live recognizer reports mic levels continuously (also during pauses). Silence from it
            // for 6 s means it stopped processing without an error: replace it right away instead
            // of leaving the bubble "listening" to nothing.
            usingAudioSource && builtinListening && !restartPending && rmsSeenThisListen && rmsSilentFor > 6_000 -> {
                Log.w(tag, "WATCHDOG: recognizer stopped reporting audio for ${rmsSilentFor / 1000}s; recreating")
                builtinListening = false
                settleLivePhrase("recognizer stalled")
                restartBuiltin(0, "watchdog: recognizer stalled", recreate = true)
            }
            builtinListening && !restartPending && idle > 90_000 -> {
                Log.w(tag, "WATCHDOG: no recognizer callback for ${idle / 1000}s; recreating")
                builtinListening = false
                restartBuiltin(0, "watchdog: stuck", recreate = true)
            }
            else -> scheduleWatchdog(session)
        }
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
            if (state == State.RECORDING) voice(rmsdB > SPEAKING_RMS_DB)
            val now = SystemClock.elapsedRealtime()
            lastRmsAt = now
            rmsSeenThisListen = true
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
            Log.i(tag, "Built-in: onSegmentResults ${spoken(text)}")
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
            Log.i(tag, "Built-in: onResults ${all.size} alternative(s), best=${spoken(text)} (active=$builtinActive)")
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
        /** Built-in recognizer level (dB) above which we treat it as speech, for the bubble animation. */
        const val SPEAKING_RMS_DB = 4.5f
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
