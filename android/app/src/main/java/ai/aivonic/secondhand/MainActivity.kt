package ai.aivonic.secondhand

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import ai.aivonic.secondhand.ui.SecondHandApp
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
  private val job: JobModel by viewModels()

  /** Linked to the glasses through the Meta AI app (Developer Mode). */
  val registered = MutableStateFlow(false)
  val registering = MutableStateFlow(false)

  private val androidPermissions =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startWearables()
        else Log.w(TAG, "permissions refused: ${result.filterValues { !it }.keys}")
      }

  private var pending: CancellableContinuation<PermissionStatus>? = null
  private val lock = Mutex()
  private val glassesPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        pending?.resume(result.getOrDefault(PermissionStatus.Denied))
        pending = null
      }

  /** Ask the glasses for one permission, through the Meta AI app, unless it is already granted. */
  suspend fun ensure(permission: Permission): Boolean {
    val now = Wearables.checkPermissionStatus(permission).fold(onSuccess = { it }, onFailure = { _, _ -> null })
    if (now == PermissionStatus.Granted) return true
    val asked =
        lock.withLock {
          suspendCancellableCoroutine { c ->
            pending = c
            c.invokeOnCancellation { pending = null }
            glassesPermission.launch(permission)
          }
        }
    return asked == PermissionStatus.Granted
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // a job is hands-free: the phone stays awake
    setContent {
      SecondHandApp(
          job = job,
          activity = this,
          onConnectGlasses = { Wearables.startRegistration(this) },
          onStartJob = { useGlasses ->
            lifecycleScope.launch {
              var mic = false
              if (useGlasses) {
                if (!ensure(Permission.CAMERA)) {
                  job.say("The glasses' camera was not allowed")
                  return@launch
                }
                mic = ensure(Permission.MICROPHONE)
              }
              job.startJob(useGlasses, glassesMic = mic)
            }
          },
      )
    }
  }

  override fun onStart() {
    super.onStart()
    androidPermissions.launch(
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO),
    )
  }

  private var started = false

  private fun startWearables() {
    if (started) return
    started = true
    Wearables.initialize(this).onFailure { error, _ -> Log.e(TAG, "DAT init: ${error.description}") }
    lifecycleScope.launch {
      Wearables.registrationState.collect { s ->
        registered.value = s == RegistrationState.REGISTERED
        registering.value = s == RegistrationState.REGISTERING
      }
    }
  }

  companion object {
    private const val TAG = "SecondHand"
  }
}
