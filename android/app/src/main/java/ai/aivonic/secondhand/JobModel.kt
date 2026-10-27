package ai.aivonic.secondhand

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One answer from the agent, as the phone shows it. */
data class Answer(
    val heard: String,
    val say: String,
    val stop: String,
    val displayOriginal: String,
    val displayEnglish: String,
    val thinkSeconds: Double,
)

data class JobState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val problem: String? = null,
    val glasses: Glasses.Status = Glasses.Status.OFF,
    val glassesProblem: String? = null,
    val mic: String = "none",
    val hearing: String = "",
    val thinking: Boolean = false,
    val speaking: Boolean = false,
    val answers: List<Answer> = emptyList(),
    val steps: List<String> = emptyList(),
    val stops: List<String> = emptyList(),
    val photo: Bitmap? = null,
    val outlined: String? = null,
    val done: Boolean = false,
)

/**
 * A job: the phone is the go-between. Microphone audio goes to the server as it is heard; when the server says
 * the technician started a question, a photo is taken through the glasses and sent; the answer comes back as
 * text (shown here) and speech (played on the glasses). While the reply plays, the microphone sends silence, so
 * the agent does not hear itself and the speech recogniser's clock keeps running.
 */
class JobModel(app: Application) : AndroidViewModel(app) {
  private val _state = MutableStateFlow(JobState())
  val state: StateFlow<JobState> = _state.asStateFlow()

  private val prefs = app.getSharedPreferences("secondhand", Context.MODE_PRIVATE)
  var server: String
    get() = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
    set(v) = prefs.edit().putString("server", v.trim()).apply()
  var token: String
    get() = prefs.getString("token", "") ?: ""
    set(v) = prefs.edit().putString("token", v.trim()).apply()

  // one reader, in arrival order: the reply's audio chunks must reach the speaker in sequence
  private val inbox = Channel<Heard>(Channel.UNLIMITED)
  private val link = Link { msg -> inbox.trySend(msg) }
  private val speaker = Speaker(app)
  private val glasses = Glasses(viewModelScope, ::onMic)
  private val fallback = FallbackMic(app, ::onMic)
  @Volatile private var muted = false
  private var lastHeard = ""
  private var photoBusy = false
  private var rawPhoto: Bitmap? = null

  init {
    viewModelScope.launch { for (msg in inbox) onServer(msg) }
    viewModelScope.launch { glasses.status.collect { s -> _state.update { it.copy(glasses = s) } } }
    viewModelScope.launch { glasses.error.collect { e -> _state.update { it.copy(glassesProblem = e) } } }
  }

  /** Start a job: open the server link, start the glasses, and make sure some microphone is listening. */
  fun startJob(useGlasses: Boolean, glassesMic: Boolean) {
    if (token.isBlank()) {
      _state.update { it.copy(problem = "Enter the device token first") }
      return
    }
    _state.value = JobState(connecting = true)
    link.open(server, token)
    viewModelScope.launch {
      if (useGlasses) {
        glasses.start(withAudio = glassesMic)
        // the glasses' microphone arrives inside the stream; if none has come three seconds after the stream
        // started (not granted, or not supported), fall back to Bluetooth hands-free or the phone
        waitFor(15_000) { _state.value.glasses == Glasses.Status.READY }
        if (glassesMic && _state.value.glasses == Glasses.Status.READY) waitFor(3_000) { glasses.audioArrived }
      }
      if (useGlasses && glasses.audioArrived) {
        _state.update { it.copy(mic = "glasses (in the camera stream)") }
      } else {
        fallback.start()
        _state.update { it.copy(mic = fallback.source) }
      }
    }
  }

  /** Show a problem on the Live screen. */
  fun say(problem: String) {
    _state.update { it.copy(problem = problem) }
  }

  private suspend fun waitFor(ms: Long, ok: () -> Boolean) {
    var waited = 0L
    while (!ok() && waited < ms) {
      delay(100)
      waited += 100
    }
  }

  fun endJob() {
    link.close()
    glasses.stop()
    fallback.stop()
    speaker.end()
    _state.update { it.copy(connected = false, connecting = false, mic = "none", speaking = false, thinking = false) }
  }

  fun ask(text: String) {
    if (text.isBlank()) return
    lastHeard = text.trim()
    _state.update { it.copy(hearing = lastHeard, thinking = true) }
    viewModelScope.launch { sendPhoto() }
    link.sendText(lastHeard)
  }

  /** Take a photo now and send it (a question is starting, or the user pressed the button). */
  fun snap() {
    viewModelScope.launch { sendPhoto() }
  }

  private fun onMic(pcm: ByteArray) {
    link.sendAudio(if (muted) ByteArray(pcm.size) else pcm)
  }

  private suspend fun sendPhoto() {
    if (photoBusy || _state.value.glasses != Glasses.Status.READY) return
    photoBusy = true
    try {
      val bitmap = glasses.photo() ?: return
      val (jpeg, w, h) = withContext(Dispatchers.Default) { encode(bitmap) }
      link.sendPhoto(jpeg, w, h)
      rawPhoto = bitmap
      _state.update { it.copy(photo = bitmap, outlined = null) }
    } finally {
      photoBusy = false
    }
  }

  private suspend fun onServer(msg: Heard) {
    when (msg) {
      Heard.Open -> _state.update { it.copy(connected = true, connecting = false, problem = null) }
      is Heard.Closed -> {
        _state.update { it.copy(connected = false, connecting = false, problem = msg.reason) }
        fallback.stop()
        glasses.stop()
      }
      is Heard.Audio -> withContext(Dispatchers.IO) { speaker.write(msg.bytes) }
      is Heard.Text -> onEvent(msg.msg)
    }
  }

  private suspend fun onEvent(m: JSONObject) {
    when (m.optString("type")) {
      "need_photo" -> viewModelScope.launch { sendPhoto() }
      "heard" -> {
        val text = m.optString("text")
        _state.update { it.copy(hearing = text, thinking = m.optBoolean("final")) }
        if (m.optBoolean("final")) lastHeard = text
      }
      "answer" -> {
        val a =
            Answer(
                heard = lastHeard,
                say = m.optString("say"),
                stop = m.optString("stop"),
                displayOriginal = m.optString("display_original"),
                displayEnglish = m.optString("display_english"),
                thinkSeconds = m.optDouble("think_s", 0.0),
            )
        val step = m.optString("step")
        _state.update {
          it.copy(
              thinking = false,
              answers = it.answers + a,
              steps = if (step.isNotBlank()) it.steps + step else it.steps,
              stops = if (a.stop.isNotBlank()) it.stops + a.stop else it.stops,
              done = it.done || m.optBoolean("job_done"),
          )
        }
      }
      "audio_start" -> {
        muted = true
        withContext(Dispatchers.IO) { speaker.begin() }
        _state.update { it.copy(speaking = true) }
      }
      "audio_end" ->
          viewModelScope.launch {
            // the bytes have arrived; let the glasses finish saying them before listening again
            while (speaker.remainingSeconds() > 0.05) delay(50)
            delay(250) // the room's echo of the last word
            speaker.end()
            muted = false
            _state.update { it.copy(speaking = false) }
          }
      "outline" -> {
        val photo = rawPhoto ?: return
        val box = m.optJSONArray("box") ?: return
        val outlined =
            withContext(Dispatchers.Default) {
              outline(
                  photo,
                  m.optString("part"),
                  RectF(
                      box.getDouble(0).toFloat(),
                      box.getDouble(1).toFloat(),
                      box.getDouble(2).toFloat(),
                      box.getDouble(3).toFloat(),
                  ),
                  m.optInt("w", photo.width),
                  m.optInt("h", photo.height),
              )
            }
        _state.update { it.copy(photo = outlined, outlined = m.optString("part")) }
      }
      else -> Log.w(TAG, "unknown message ${m.optString("type")}")
    }
  }

  override fun onCleared() {
    endJob()
    super.onCleared()
  }

  companion object {
    private const val TAG = "SecondHand.Job"
    const val DEFAULT_SERVER = "wss://secondhand.aivonic.ai"
    private const val MAX_SIDE = 1280

    /** JPEG at most 1280 px on the long side: enough to read a rating plate, small enough to send at once. */
    fun encode(bitmap: Bitmap): Triple<ByteArray, Int, Int> {
      val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
      val sized =
          if (scale < 1f)
              Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
          else bitmap
      val out = ByteArrayOutputStream()
      sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
      return Triple(out.toByteArray(), sized.width, sized.height)
    }

    /** The photo with the part the agent named drawn round, in the photo's own pixels. */
    fun outline(photo: Bitmap, part: String, box: RectF, w: Int, h: Int): Bitmap {
      val copy = photo.copy(Bitmap.Config.ARGB_8888, true)
      val sx = copy.width / w.toFloat()
      val sy = copy.height / h.toFloat()
      val r = RectF(box.left * sx, box.top * sy, box.right * sx, box.bottom * sy)
      val stroke = copy.width / 120f
      val canvas = Canvas(copy)
      canvas.drawRoundRect(
          r,
          stroke * 2,
          stroke * 2,
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = 0xFFF2A900.toInt()
          },
      )
      val text =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = copy.width / 22f
            color = 0xFF1A1A1A.toInt()
            isFakeBoldText = true
          }
      val pad = stroke * 1.5f
      val tw = text.measureText(part)
      val top = (r.top - text.textSize - pad * 2).coerceAtLeast(0f)
      canvas.drawRoundRect(
          RectF(r.left, top, r.left + tw + pad * 2, top + text.textSize + pad * 2),
          stroke,
          stroke,
          Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2A900.toInt() },
      )
      canvas.drawText(part, r.left + pad, top + pad + text.textSize * 0.85f, text)
      return copy
    }
  }
}
