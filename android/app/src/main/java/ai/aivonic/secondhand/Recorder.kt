package ai.aivonic.secondhand

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The wearer's view as a film. The glasses cannot record on their own while an app holds their camera, so the app
 * films it: the camera stream's own HEVC frames go straight into an MP4 (never decoded, so it costs the phone
 * almost nothing), with one audio track mixing the wearer's microphone and the agent's spoken replies (16 kHz
 * mono, both, encoded to AAC). The film starts at the stream's first keyframe and is saved to Movies/Second Hand
 * when the job ends.
 */
class Recorder(private val context: Context) {
  private val lock = Any()
  private val name = "second-hand-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".mp4"
  /** The film itself, kept in the app's own folder so it can always be shared, whatever happens to the Movies copy. */
  val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir, name)

  private var muxer: MediaMuxer? = null
  private var aac: MediaCodec? = null
  private var videoTrack = -1
  private var audioTrack = -1
  private var paramSets: ByteArray? = null
  private var startNs = 0L
  private var lastVideoUs = -1L
  private var lastAudioUs = -1L
  private var closed = false
  private var seen = 0
  @Volatile var frames = 0
    private set

  // the audio timeline, in samples since the film started: both voices are added into it, then encoded in order
  private val mix = IntArray(SAMPLE_RATE * MIX_SECONDS)
  private var mixStart = 0L // the sample index of mix[0]
  private var emitted = 0L // the next sample to encode
  private var micAt = 0L
  private var micStarted = false
  private var speakerAt = 0L

  val started: Boolean
    get() = synchronized(lock) { muxer != null }

  /** One frame of the glasses' camera stream. */
  fun video(frame: VideoFrame) {
    if (!frame.isCompressed) return
    val src = frame.buffer.duplicate()
    val bytes = ByteArray(src.remaining()).also { src.get(it) }
    if (bytes.isEmpty()) return
    val annexB = Hevc.toAnnexB(bytes)
    if (seen++ < 3) {
      val head = bytes.take(12).joinToString(" ") { "%02x".format(it) }
      Log.i(TAG, "frame ${frame.width}x${frame.height} config=${frame.isCodecConfig} ${bytes.size} B: $head")
    }
    synchronized(lock) {
      if (closed) return
      if (frame.isCodecConfig) {
        paramSets = annexB
        return
      }
      if (paramSets == null) Hevc.paramSets(annexB)?.let { paramSets = it }
      val key = Hevc.isKeyframe(annexB)
      if (muxer == null) {
        val csd = paramSets
        if (csd == null || !key) return
        if (!open(frame.width, frame.height, csd)) return
      }
      val m = muxer ?: return
      var us = (System.nanoTime() - startNs) / 1000
      if (us <= lastVideoUs) us = lastVideoUs + 1
      lastVideoUs = us
      val info = MediaCodec.BufferInfo().apply {
        set(0, annexB.size, us, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
      }
      m.writeSampleData(videoTrack, ByteBuffer.wrap(annexB), info)
      frames++
      encodeUpTo(now() - LAG)
    }
  }

  /** The wearer's microphone, 16 kHz mono 16-bit PCM, as it is heard. */
  fun mic(pcm: ByteArray) {
    synchronized(lock) {
      if (muxer == null || closed) return
      val now = now()
      // the microphone shares the radio link with the video, so it arrives in bursts: a stalled second comes all at
      // once. Lay it end to end by its own sample count (by arrival time a burst lands on top of itself and the words
      // turn to mush); start again at now only when it has fallen behind what is already encoded.
      val at = if (!micStarted || micAt < emitted) now else micAt
      micStarted = true
      micAt = at + add(at, pcm)
      encodeUpTo(now - LAG)
    }
  }

  /** The agent's reply, 16 kHz mono 16-bit PCM, as it is handed to the speaker (faster than it plays). */
  fun speaker(pcm: ByteArray) {
    synchronized(lock) {
      if (muxer == null || closed) return
      val at = maxOf(speakerAt, now())
      speakerAt = at + add(at, pcm)
    }
  }

  /** Close the film and put it in Movies/Second Hand. Returns where it is, or throws with the reason. */
  fun finish(): String {
    synchronized(lock) {
      if (closed) error("already closed")
      closed = true
      val m = muxer
      if (m == null) {
        release()
        file.delete()
        error("no video came from the glasses")
      }
      encodeUpTo(maxOf(micAt, speakerAt, now()), endOfStream = true)
      runCatching { m.stop() }.onFailure { Log.w(TAG, "muxer stop", it) }
      release()
    }
    Log.i(TAG, "film: $frames frames, ${file.length()} bytes")
    return publish()
  }

  private fun open(width: Int, height: Int, csd: ByteArray): Boolean {
    return try {
      val encoder =
          MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                  setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                  setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                  setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_SAMPLES * 2)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            start()
          }
      file.parentFile?.mkdirs()
      val m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      videoTrack =
          m.addTrack(
              MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
              },
          )
      // AAC-LC, 16 kHz, mono: AudioSpecificConfig 00010 1000 0001 000
      audioTrack =
          m.addTrack(
              MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x14, 0x08)))
              },
          )
      m.start()
      aac = encoder
      muxer = m
      startNs = System.nanoTime()
      Log.i(TAG, "film started: ${width}x$height, ${file.name}")
      true
    } catch (e: Exception) {
      Log.e(TAG, "film could not start", e)
      release()
      false
    }
  }

  private fun now(): Long = (System.nanoTime() - startNs) * SAMPLE_RATE / 1_000_000_000L

  /** Add PCM into the timeline at [at]; returns the number of samples it holds. */
  private fun add(at: Long, pcm: ByteArray): Int {
    val n = pcm.size / 2
    for (i in 0 until n) {
      val idx = (at + i - mixStart).toInt()
      if (idx < 0 || idx >= mix.size) continue
      if (at + i < emitted) continue // already encoded: too late to mix in
      val s = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)
      mix[idx] += s
    }
    return n
  }

  private fun encodeUpTo(until: Long, endOfStream: Boolean = false) {
    val codec = aac ?: return
    val chunk = ByteArray(CHUNK_SAMPLES * 2)
    while (emitted < until) {
      val n = minOf(CHUNK_SAMPLES.toLong(), until - emitted).toInt()
      for (i in 0 until n) {
        val idx = (emitted + i - mixStart).toInt()
        val s = if (idx in mix.indices) mix[idx].coerceIn(-32768, 32767) else 0
        chunk[2 * i] = s.toByte()
        chunk[2 * i + 1] = (s shr 8).toByte()
      }
      val inIndex = codec.dequeueInputBuffer(10_000)
      if (inIndex < 0) break
      codec.getInputBuffer(inIndex)?.apply {
        clear()
        put(chunk, 0, n * 2)
      }
      codec.queueInputBuffer(inIndex, 0, n * 2, emitted * 1_000_000L / SAMPLE_RATE, 0)
      emitted += n
      drain(codec, false)
    }
    // slide the timeline once most of it has been encoded
    val used = (emitted - mixStart).toInt()
    if (used > mix.size / 2) {
      System.arraycopy(mix, used, mix, 0, mix.size - used)
      java.util.Arrays.fill(mix, mix.size - used, mix.size, 0)
      mixStart = emitted
    }
    if (endOfStream) {
      val inIndex = codec.dequeueInputBuffer(10_000)
      if (inIndex >= 0) {
        codec.queueInputBuffer(inIndex, 0, 0, emitted * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(codec, true)
      }
    }
  }

  private fun drain(codec: MediaCodec, untilEnd: Boolean) {
    val m = muxer ?: return
    val info = MediaCodec.BufferInfo()
    var waits = 0
    while (true) {
      val out = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
      when {
        out >= 0 -> {
          val buf = codec.getOutputBuffer(out)
          val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
          if (buf != null && info.size > 0 && !config) {
            if (info.presentationTimeUs <= lastAudioUs) info.presentationTimeUs = lastAudioUs + 1
            lastAudioUs = info.presentationTimeUs
            m.writeSampleData(audioTrack, buf, info)
          }
          codec.releaseOutputBuffer(out, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
        out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd || ++waits > 50) return
        else -> Unit // output format changed: the track was added with its own format
      }
    }
  }

  private fun release() {
    runCatching { aac?.stop() }
    runCatching { aac?.release() }
    runCatching { muxer?.release() }
    aac = null
    muxer = null
  }

  /** Copy the film into the shared Movies folder; the private copy stays, for Share film. */
  private fun publish(): String {
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, name)
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Second Hand")
          put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    val uri =
        resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return "the app's own folder (Movies refused the file)"
    return try {
      resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
          ?: error("could not write to Movies")
      resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
      "Movies/Second Hand/$name"
    } catch (e: Exception) {
      Log.e(TAG, "film copy", e)
      runCatching { resolver.delete(uri, null, null) }
      "the app's own folder (Movies failed: ${e.message})"
    }
  }

  companion object {
    private const val TAG = "SecondHand.Film"
    private const val MIX_SECONDS = 20
    private const val CHUNK_SAMPLES = 1024
    private const val LAG = SAMPLE_RATE * 3L / 2 // 1.5 s: room for a stalled burst of microphone to arrive before encoding
  }
}

/** Just enough HEVC to mux the glasses' stream: find parameter sets and keyframes, and normalise to Annex B. */
internal object Hevc {
  private fun nals(b: ByteArray): List<Pair<Int, Int>> {
    // (start of NAL payload, end) for each start code 00 00 01 / 00 00 00 01
    val starts = mutableListOf<Int>()
    var i = 0
    while (i + 2 < b.size) {
      if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
        starts += i + 3
        i += 3
      } else i++
    }
    return starts.mapIndexed { k, s ->
      var end = if (k + 1 < starts.size) starts[k + 1] - 3 else b.size
      if (k + 1 < starts.size && end > s && b[end - 1].toInt() == 0) end-- // the 4-byte form's leading zero
      s to end
    }
  }

  private fun type(b: ByteArray, at: Int): Int = (b[at].toInt() shr 1) and 0x3f

  /**
   * The stream may arrive length-prefixed (4-byte big-endian lengths) rather than with start codes. A buffer is
   * taken as length-prefixed only if its lengths tile it exactly and every NAL header is well formed; a start
   * code read as a length cannot do that, so Annex B passes through unchanged.
   */
  fun toAnnexB(b: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(b.size + 16)
    var i = 0
    var count = 0
    while (i + 4 <= b.size) {
      val len = ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
          ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
      if (len < 2 || i + 4 + len > b.size) return b
      if (b[i + 4].toInt() and 0x80 != 0) return b // forbidden_zero_bit
      out.write(byteArrayOf(0, 0, 0, 1))
      out.write(b, i + 4, len)
      i += 4 + len
      count++
    }
    return if (i == b.size && count > 0) out.toByteArray() else b
  }

  /** VPS, SPS and PPS (types 32-34) inside an access unit, as Annex B, or null. */
  fun paramSets(b: ByteArray): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    for ((s, e) in nals(b)) {
      if (s < e && type(b, s) in 32..34) {
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(b, s, e - s)
      }
    }
    return out.toByteArray().takeIf { it.isNotEmpty() }
  }

  /** An IRAP picture (BLA, IDR or CRA: types 16-21): a film may start here. */
  fun isKeyframe(b: ByteArray): Boolean = nals(b).any { (s, e) -> s < e && type(b, s) in 16..21 }
}
