package ai.aivonic.secondhand

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

const val SAMPLE_RATE = 16_000

/**
 * The microphone when the glasses' stream carries none: the glasses over Bluetooth hands-free (SCO) if they are
 * connected, otherwise the phone's own microphone, so the app can be tested without glasses. [source] says which.
 * Same format as the stream: 16 kHz mono 16-bit PCM.
 */
class FallbackMic(context: Context, private val onAudio: (ByteArray) -> Unit) {
  private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  @Volatile private var running = false
  private var thread: Thread? = null
  private var routedSco = false
  var source = "none"
    private set

  @SuppressLint("MissingPermission") // RECORD_AUDIO is requested before any session starts
  suspend fun start() {
    if (running) return
    val sco = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    if (sco != null) {
      audio.mode = AudioManager.MODE_IN_COMMUNICATION
      routedSco =
          audio.setCommunicationDevice(sco) &&
              withTimeoutOrNull(3000) {
                while (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) delay(100)
                true
              } == true
      if (!routedSco) release()
    }
    source = if (routedSco) "glasses (Bluetooth hands-free)" else "phone microphone"
    val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    // VOICE_RECOGNITION: no echo canceller or noise gate shaping the signal before speech recognition
    val record =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, CHUNK * 4),
        )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      record.release()
      source = "none"
      return
    }
    running = true
    thread =
        Thread {
              record.startRecording()
              val buf = ByteArray(CHUNK)
              while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) onAudio(buf.copyOf(n)) else if (n < 0) break
              }
              record.stop()
              record.release()
            }
            .apply {
              name = "fallback-mic"
              start()
            }
    Log.i(TAG, "fallback mic: $source")
  }

  fun stop() {
    running = false
    thread?.join(500)
    thread = null
    release()
    source = "none"
  }

  private fun release() {
    if (routedSco || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
      audio.clearCommunicationDevice()
      audio.mode = AudioManager.MODE_NORMAL
    }
    routedSco = false
  }

  companion object {
    private const val TAG = "SecondHand.Mic"
    const val CHUNK = 2560 // 80 ms
  }
}

/**
 * Plays the agent's spoken reply, 16 kHz mono PCM, while it is still arriving. It goes where the phone's audio
 * goes: the glasses' speakers, over the media link, or over hands-free when the fallback microphone holds a call
 * route open (a line spoken in a call goes down that call's link).
 */
class Speaker(context: Context) {
  private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var track: AudioTrack? = null
  private var written = 0L

  fun begin() {
    end()
    val usage =
        if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) AudioAttributes.USAGE_VOICE_COMMUNICATION
        else AudioAttributes.USAGE_MEDIA
    val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    track =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE)) // half a second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    written = 0
  }

  private var odd: Byte? = null

  /** Called off the main thread: a write blocks while the track's buffer is full. */
  fun write(chunk: ByteArray) {
    val t = track ?: return
    // network chunks need not end on a sample boundary; carry a stray byte over
    var bytes = chunk
    odd?.let { bytes = byteArrayOf(it) + bytes }
    odd = if (bytes.size % 2 == 1) bytes.last() else null
    val n = bytes.size - (bytes.size % 2)
    if (n > 0) {
      t.write(bytes, 0, n)
      written += n / 2
    }
  }

  /** Seconds of the reply still to be heard. */
  fun remainingSeconds(): Double {
    val t = track ?: return 0.0
    return ((written - t.playbackHeadPosition.toLong()).coerceAtLeast(0)) / SAMPLE_RATE.toDouble()
  }

  fun end() {
    track?.run {
      runCatching { stop() }
      release()
    }
    track = null
    odd = null
  }
}
