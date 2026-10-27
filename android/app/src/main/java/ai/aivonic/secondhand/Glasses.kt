package ai.aivonic.secondhand

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioFrame
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The glasses: one device session with one camera stream, kept small (360x640 at 7 fps, compressed, never
 * decoded), because the stream is only there to make photos and to carry the microphone. The wearer's own eyes
 * are the viewfinder. Compressed rather than raw because the SDK keeps a compressed stream flowing when the phone
 * is locked. The microphone rides the same stream as 16 kHz mono PCM, when the glasses grant it.
 */
class Glasses(private val scope: CoroutineScope, private val onAudio: (ByteArray) -> Unit) {
  enum class Status { OFF, CONNECTING, READY, STOPPED }

  private val _status = MutableStateFlow(Status.OFF)
  val status: StateFlow<Status> = _status.asStateFlow()
  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /** True once the first audio frame from the glasses has arrived in this session. */
  @Volatile var audioArrived = false
    private set

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private val jobs = mutableListOf<Job>()

  fun start(withAudio: Boolean) {
    if (session != null) return
    audioArrived = false
    _error.value = null
    _status.value = Status.CONNECTING
    Wearables.createSession(AutoDeviceSelector())
        .onSuccess { created ->
          session = created
          jobs += scope.launch { created.errors.collect { _error.value = it.description } }
          jobs += scope.launch {
            created.state.collect { state ->
              Log.i(TAG, "session $state")
              // a capability can only be added once the session has started
              if (state == DeviceSessionState.STARTED && camera == null) attachCamera(created, withAudio)
              if (state == DeviceSessionState.STOPPED) teardown(Status.STOPPED)
            }
          }
          created.start()
        }
        .onFailure { error, _ ->
          _error.value = error.description
          _status.value = Status.OFF
        }
  }

  private fun attachCamera(session: DeviceSession, withAudio: Boolean) {
    session
        .addCamera(
            StreamConfiguration(
                audioCodec = if (withAudio) AudioCodec.PCM(AudioSampleRate.RATE_16000, 1) else null,
                videoQuality = VideoQuality.LOW,
                frameRate = 7,
                compressVideo = true,
            ),
        )
        .onSuccess { added ->
          camera = added
          if (withAudio) jobs += scope.launch(Dispatchers.IO) { added.stream.audioStream.collect(::receive) }
          jobs += scope.launch {
            added.stream.state.collect { state ->
              Log.i(TAG, "stream $state")
              when (state) {
                StreamState.STREAMING -> _status.value = Status.READY
                StreamState.STOPPED, StreamState.CLOSED ->
                    if (_status.value == Status.READY) teardown(Status.STOPPED)
                else -> Unit
              }
            }
          }
          jobs += scope.launch { added.stream.errorStream.collect { _error.value = it.description } }
          added.stream.start().onFailure { error, _ -> _error.value = error.description }
        }
        .onFailure { error, _ -> _error.value = error.description }
  }

  private fun receive(frame: AudioFrame) {
    // 16-bit PCM in the platform's byte order (little-endian on Android), mono, 16 kHz: what the server takes
    val buffer = frame.buffer.duplicate()
    if (!buffer.hasRemaining()) return
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    audioArrived = true
    onAudio(bytes)
  }

  /** A photo from the glasses, upright, or null (with the reason in [error]). */
  suspend fun photo(): Bitmap? {
    val stream = camera?.stream ?: return null
    val data: PhotoData =
        stream
            .capturePhoto()
            .fold(
                onSuccess = { it },
                onFailure = { error, _ ->
                  _error.value = error.description
                  null
                },
            ) ?: return null
    return withContext(Dispatchers.Default) { decode(data) }
  }

  fun stop() {
    session?.stop()
    teardown(Status.OFF)
  }

  private fun teardown(next: Status) {
    jobs.forEach { it.cancel() }
    jobs.clear()
    runCatching { camera?.stop() }
    camera = null
    session = null
    _status.value = next
  }

  private fun decode(photo: PhotoData): Bitmap? =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
          val buffer = photo.data.duplicate().apply { rewind() }
          val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { upright(it, bytes) }
        }
      }

  /** The glasses record orientation in EXIF, which the decoder does not apply to HEIC. */
  private fun upright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
    val orientation =
        runCatching {
              ExifInterface(ByteArrayInputStream(bytes))
                  .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees =
        when (orientation) {
          ExifInterface.ORIENTATION_ROTATE_90 -> 90f
          ExifInterface.ORIENTATION_ROTATE_180 -> 180f
          ExifInterface.ORIENTATION_ROTATE_270 -> 270f
          else -> return bitmap
        }
    val rotated =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    bitmap.recycle()
    return rotated
  }

  companion object {
    private const val TAG = "SecondHand.Glasses"
  }
}
