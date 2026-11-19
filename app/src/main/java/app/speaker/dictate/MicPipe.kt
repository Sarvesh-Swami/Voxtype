package app.speaker.dictate

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max

/**
 * Keeps the microphone open for the whole dictation session and streams raw audio (16 kHz mono PCM16)
 * into whichever recognizer is currently listening, through a pipe (RecognizerIntent.EXTRA_AUDIO_SOURCE).
 *
 * The recognizer therefore never opens/closes the mic itself: no "mic on/off" between utterances and no
 * gaps. While no recognizer is attached (e.g. during a restart) audio is kept in a short ring buffer and
 * replayed into the next pipe, so words spoken during a restart aren't lost.
 */
class MicPipe(private val events: Events? = null) {
    /** Called on the mic thread. */
    interface Events {
        /** The recognizer closed its end of the stream without us asking (it stopped listening). */
        fun onPipeClosedByRecognizer()
        /** Android started/stopped feeding us silence because another app took the microphone. */
        fun onMicSilenced(silenced: Boolean)
        /** The microphone could not be (re)opened; the stream has ended. */
        fun onMicFailed(error: Exception)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "Speaker.micpipe"
        private const val FRAME_BYTES = 2048          // 64 ms
        private const val RING_BYTES = SAMPLE_RATE * 2 * 2 // keep the last 2 s while detached (fits the pipe buffer)
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private val lock = Object()
    private var sink: OutputStream? = null
    private var sinkId = 0
    private val ring = ArrayDeque<ByteArray>()
    private var ringBytes = 0
    @Volatile var bytesStreamed = 0L
        private set

    @SuppressLint("MissingPermission") // DictationEngine checks RECORD_AUDIO first
    private fun openRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 16_384) * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone is unavailable (another app may be using it)")
        }
        record.startRecording()
        return record
    }

    fun start() {
        val record = openRecord()
        running = true
        Log.i(TAG, "Mic opened for the session (continuous stream)")
        thread = Thread({ loop(record) }, "mic-pipe").also { it.start() }
    }

    /** Android 10+: true while the OS feeds us silence because another app holds the mic. */
    private fun isSilenced(record: AudioRecord): Boolean =
        android.os.Build.VERSION.SDK_INT >= 29 &&
            runCatching { record.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)

    private fun loop(initial: AudioRecord) {
        var record = initial
        var readErrors = 0
        var silenced = false
        var lastSilenceCheck = 0L
        try {
            while (running) {
                val frame = ByteArray(FRAME_BYTES)
                val n = record.read(frame, 0, FRAME_BYTES)
                if (n <= 0) {
                    if (n < 0) {
                        readErrors++
                        if (readErrors % 20 == 1) Log.w(TAG, "AudioRecord.read returned $n (x$readErrors)")
                        if (readErrors % 25 == 0) {
                            // Dead recorder (audio server restarted, device routing changed): a dead
                            // AudioRecord never recovers, so reopen the mic and carry on.
                            Log.w(TAG, "Reopening the microphone after $readErrors read errors")
                            runCatching { record.stop() }
                            record.release()
                            Thread.sleep(300)
                            record = runCatching { openRecord() }.getOrElse {
                                Log.e(TAG, "Mic reopen failed; retrying in 1s", it)
                                Thread.sleep(1000)
                                openRecord()
                            }
                        }
                    }
                    Thread.sleep(20)
                    continue
                }
                readErrors = 0
                val now = System.currentTimeMillis()
                if (now - lastSilenceCheck > 1000) {
                    lastSilenceCheck = now
                    val s = isSilenced(record)
                    if (s != silenced) {
                        silenced = s
                        Log.w(TAG, if (s) "Android is SILENCING our mic (another app took it)" else "Mic is live again")
                        events?.onMicSilenced(s)
                    }
                }
                val chunk = if (n == FRAME_BYTES) frame else frame.copyOf(n)
                var closedByRecognizer = false
                synchronized(lock) {
                    val out = sink
                    if (out != null) {
                        try {
                            out.write(chunk)
                            bytesStreamed += n
                        } catch (e: IOException) {
                            // Recognizer stopped and closed its end: buffer until the next one attaches.
                            Log.i(TAG, "Pipe #$sinkId closed by recognizer (${e.message}); buffering")
                            runCatching { out.close() }
                            sink = null
                            remember(chunk)
                            closedByRecognizer = true
                        }
                    } else {
                        remember(chunk)
                    }
                }
                // Outside the lock: the engine reacts by attaching a new pipe.
                if (closedByRecognizer) events?.onPipeClosedByRecognizer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mic loop failed", e)
            if (running) events?.onMicFailed(e)
        } finally {
            runCatching { record.stop() }
            record.release()
            synchronized(lock) { runCatching { sink?.close() }; sink = null }
            Log.i(TAG, "Mic closed; streamed ${bytesStreamed / (SAMPLE_RATE * 2)} s of audio in total")
        }
    }

    private fun remember(chunk: ByteArray) {
        ring.addLast(chunk)
        ringBytes += chunk.size
        while (ringBytes > RING_BYTES) ringBytes -= ring.removeFirst().size
    }

    /**
     * Creates a new pipe for the next recognizer and returns its read end (put it in EXTRA_AUDIO_SOURCE).
     * Audio buffered since the previous recognizer stopped is replayed first.
     */
    fun attachNew(): ParcelFileDescriptor {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        // Non-blocking writes: if the recognizer falls behind, audio queues here instead of freezing the mic thread.
        // (MicPipe is only used on Android 13+, where audio-source recognition exists.)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching { Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK) }
                .onFailure { Log.w(TAG, "Could not make pipe non-blocking: $it") }
        }
        synchronized(lock) {
            runCatching { sink?.close() }
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
            sinkId++
            val replay = ringBytes
            val buffered = ring.toList()
            ring.clear()
            ringBytes = 0
            sink = QueueingStream(out, ArrayDeque(buffered))
            Log.i(TAG, "Pipe #$sinkId attached; replaying ${replay * 1000 / (SAMPLE_RATE * 2)} ms of buffered audio")
        }
        return readEnd
    }

    /** Ends the current stream (tells the recognizer the audio is over). */
    fun detach() {
        synchronized(lock) {
            runCatching { sink?.close() }
            sink = null
            Log.i(TAG, "Pipe #$sinkId detached by us")
        }
    }

    fun stop() {
        running = false
        thread?.join(2000)
    }

    /**
     * Writes frames in order: buffered audio first, then live frames. Each frame is 2048 bytes, which is
     * below PIPE_BUF, so a write is atomic: all of it, or EAGAIN (pipe full) and it waits in the queue.
     * Any other error (recognizer closed its end) is thrown to the caller.
     */
    private class QueueingStream(private val out: OutputStream, private val pending: ArrayDeque<ByteArray>) : OutputStream() {
        private var pendingBytes = pending.sumOf { it.size }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            val frame = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            pending.addLast(frame)
            pendingBytes += frame.size
            while (pendingBytes > RING_BYTES) pendingBytes -= pending.removeFirst().size // drop oldest if far behind
            while (pending.isNotEmpty()) {
                try {
                    out.write(pending.first())
                } catch (e: IOException) {
                    val errno = (e.cause as? ErrnoException)?.errno
                    if (errno == OsConstants.EAGAIN || e.message?.contains("EAGAIN") == true) return // full; retry next frame
                    throw e
                }
                pendingBytes -= pending.removeFirst().size
            }
        }

        override fun close() = out.close()
    }
}
