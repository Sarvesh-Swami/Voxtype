package app.speaker.dictate

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records continuously (16 kHz mono 16-bit) until [stop] is called and never stops on its own.
 * A simple energy-based voice detector cuts the stream into phrase-sized WAV files at natural
 * pauses; each finished phrase is handed to [onSegment] (on the recorder thread) so it can be
 * transcribed and typed while recording continues. Silence-only stretches are dropped, which
 * also avoids speech models "hallucinating" text from silence.
 */
class SegmentingRecorder(
    private val dir: File,
    private val onSegment: (File, Segment) -> Unit,
) {
    data class Segment(val index: Int, val durationMs: Long, val speechMs: Long)

    private companion object {
        const val TAG = "Speaker.rec"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1024              // 64 ms per frame
        const val PAUSE_TO_CUT_MS = 900L            // silence after speech that ends a phrase
        const val MAX_SEGMENT_MS = 28_000L          // force a cut on long monologues
        const val MIN_SPEECH_MS = 250L              // less than this is treated as noise
        const val IDLE_RESET_MS = 3_000L            // drop leading silence beyond this
        const val MIN_THRESHOLD = 220.0             // RMS floor for "speech"
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var error: Exception? = null

    @SuppressLint("MissingPermission") // checked by DictationEngine before calling
    private fun openRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 16_384) * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone is unavailable (another app may be using it)")
        }
        record.startRecording()
        Log.i(TAG, "AudioRecord opened: minBuf=$minBuf recordingState=${record.recordingState} session=${record.audioSessionId}")
        return record
    }

    val isAlive get() = thread?.isAlive == true

    fun start() {
        val record = openRecord()
        running = true
        Log.i(TAG, "Recording started (continuous, VOICE_RECOGNITION source)")
        thread = Thread({ loop(record) }, "segment-recorder").also { it.start() }
    }

    /** Android 10+: tells us if the OS is feeding us silence (e.g. another app took the mic). */
    private fun silencedInfo(record: AudioRecord): String = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val cfg = record.activeRecordingConfiguration
            if (cfg == null) "config=none" else "silencedByOS=${cfg.isClientSilenced}"
        } else "silencedByOS=n/a"
    }.getOrDefault("silencedByOS=?")

    /** Stops recording. The final phrase (if it contains speech) is delivered before this returns. */
    fun stop() {
        running = false
        thread?.join(5000)
        error?.let { throw it }
    }

    private fun loop(initial: AudioRecord) {
        var record = initial
        var readErrors = 0
        var silenceResets = 0
        val frame = ShortArray(FRAME_SAMPLES)
        val bytes = ByteBuffer.allocate(FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var index = 0
        var seg = openSegment(index)
        var noise = -1.0
        var lastStatsAt = System.currentTimeMillis()
        var peakRms = 0.0
        try {
            while (running) {
                val n = record.read(frame, 0, FRAME_SAMPLES)
                if (n <= 0) {
                    if (n < 0) {
                        readErrors++
                        Log.w(TAG, "AudioRecord.read returned $n (error #$readErrors) ${silencedInfo(record)}")
                        if (readErrors % 10 == 0) {
                            // The mic was taken away or the audio server restarted: reopen and keep going.
                            Log.w(TAG, "Reopening microphone after repeated read errors")
                            runCatching { record.stop() }
                            record.release()
                            Thread.sleep(500)
                            record = runCatching { openRecord() }.getOrElse {
                                Log.e(TAG, "Reopen failed, retrying in 2s", it)
                                Thread.sleep(2000)
                                openRecord()
                            }
                        } else {
                            Thread.sleep(50)
                        }
                    }
                    continue
                }
                readErrors = 0
                var sum = 0.0
                for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                val rms = sqrt(sum / n)
                peakRms = max(peakRms, rms)

                // Adaptive noise floor: falls quickly, rises slowly.
                noise = when {
                    noise < 0 -> rms
                    rms < noise -> noise * 0.8 + rms * 0.2
                    else -> noise * 0.995 + rms * 0.005
                }
                val threshold = max(noise * 2.5, MIN_THRESHOLD)
                val isSpeech = rms > threshold
                val frameMs = n * 1000L / SAMPLE_RATE

                bytes.clear()
                for (i in 0 until n) bytes.putShort(frame[i])
                seg.raf.write(bytes.array(), 0, n * 2)
                seg.bytes += n * 2

                if (isSpeech) {
                    seg.speechMs += frameMs
                    seg.silenceMs = 0
                } else {
                    seg.silenceMs += frameMs
                }
                val segMs = seg.bytes * 1000 / (SAMPLE_RATE * 2)
                val hasSpeech = seg.speechMs >= MIN_SPEECH_MS

                if (!hasSpeech && segMs >= IDLE_RESET_MS) {
                    silenceResets++
                    seg.reset() // only silence/noise so far: throw it away, keep listening
                } else if (hasSpeech && (seg.silenceMs >= PAUSE_TO_CUT_MS || segMs >= MAX_SEGMENT_MS)) {
                    finish(seg, index)
                    index++
                    seg = openSegment(index)
                }

                val now = System.currentTimeMillis()
                if (now - lastStatsAt >= 5000) {
                    Log.d(TAG, "level: peakRms=${peakRms.toInt()} noiseFloor=${noise.toInt()} threshold=${threshold.toInt()} " +
                        "phrase#$index speechMs=${seg.speechMs} segMs=$segMs silentStretchesDropped=$silenceResets ${silencedInfo(record)}")
                    lastStatsAt = now
                    peakRms = 0.0
                }
            }
            Log.i(TAG, "Stop requested: last phrase speechMs=${seg.speechMs} -> ${if (seg.speechMs >= MIN_SPEECH_MS) "send" else "discard"}")
            if (seg.speechMs >= MIN_SPEECH_MS) finish(seg, index) else seg.discard()
        } catch (e: Exception) {
            Log.e(TAG, "Recorder loop failed", e)
            error = e
            runCatching { seg.discard() }
        } finally {
            runCatching { record.stop() }
            record.release()
            Log.i(TAG, "Recording stopped: ${index} phrase(s) sent, $silenceResets silent stretch(es) dropped")
        }
    }

    private fun finish(seg: OpenSegment, index: Int) {
        seg.raf.seek(0)
        seg.raf.write(wavHeader(seg.bytes))
        seg.raf.close()
        val info = Segment(index, seg.bytes * 1000 / (SAMPLE_RATE * 2), seg.speechMs)
        Log.i(TAG, "Phrase #$index ready: ${info.durationMs} ms audio, ${info.speechMs} ms speech")
        onSegment(seg.file, info)
    }

    private fun openSegment(index: Int): OpenSegment {
        val file = File(dir, "phrase-$index-${System.nanoTime()}.wav")
        return OpenSegment(file, RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) })
    }

    private class OpenSegment(val file: File, val raf: RandomAccessFile) {
        var bytes = 0L
        var speechMs = 0L
        var silenceMs = 0L

        fun reset() {
            raf.setLength(44)
            raf.seek(44)
            bytes = 0; speechMs = 0; silenceMs = 0
        }

        fun discard() {
            runCatching { raf.close() }
            file.delete()
        }
    }

    private fun wavHeader(dataLen: Long): ByteArray {
        val byteRate = SAMPLE_RATE * 2
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        return "RIFF".toByteArray() + le32(36 + dataLen) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(SAMPLE_RATE.toLong()) +
            le32(byteRate.toLong()) + le16(2) + le16(16) +
            "data".toByteArray() + le32(dataLen)
    }
}
