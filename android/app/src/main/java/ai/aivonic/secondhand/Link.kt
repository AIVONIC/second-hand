package ai.aivonic.secondhand

import android.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** What the server says, one message at a time (the protocol is documented in server/secondhand/app.py). */
sealed interface Heard {
  data class Text(val msg: JSONObject) : Heard
  class Audio(val bytes: ByteArray) : Heard
  data class Closed(val reason: String) : Heard
  data object Open : Heard
}

/**
 * One WebSocket per job to the Second Hand server. The key to Meta's models stays on the server; the phone
 * proves itself with the device token the user entered once.
 */
class Link(private val onMessage: (Heard) -> Unit) {
  private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
  private var socket: WebSocket? = null

  fun open(server: String, token: String) {
    close()
    val base = server.trim().trimEnd('/').replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
    val url =
        "$base/v1/session".toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("token", token.trim())
            ?.addQueryParameter("audio", "pcm16")?.build()
    if (url == null) {
      onMessage(Heard.Closed("The server address is not a URL"))
      return
    }
    socket =
        client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) = onMessage(Heard.Open)

              override fun onMessage(webSocket: WebSocket, text: String) =
                  onMessage(Heard.Text(JSONObject(text)))

              override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                  onMessage(Heard.Audio(bytes.toByteArray()))

              override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                onMessage(Heard.Closed(closedBecause(code, reason)))
              }

              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // the server refuses a wrong token by closing before the handshake completes: HTTP 403
                val why = if (response?.code == 403) "The server refused the device token" else (t.message ?: "$t")
                onMessage(Heard.Closed(why))
              }
            },
        )
  }

  private fun closedBecause(code: Int, reason: String): String =
      when (code) {
        1008 -> "The server refused the device token"
        1000 -> "Job closed"
        else -> reason.ifEmpty { "Connection closed ($code)" }
      }

  fun sendAudio(pcm: ByteArray) {
    socket?.send(pcm.toByteString())
  }

  fun sendPhoto(jpeg: ByteArray, w: Int, h: Int) {
    socket?.send(
        JSONObject()
            .put("type", "photo")
            .put("jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            .put("w", w)
            .put("h", h)
            .toString(),
    )
  }

  fun sendText(text: String) {
    socket?.send(JSONObject().put("type", "text").put("text", text).toString())
  }

  fun close() {
    socket?.close(1000, "job closed")
    socket = null
  }
}
