package dev.brahmkshatriya.echo.di

import android.app.Application
import android.content.Context.CONNECTIVITY_SERVICE
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.mayakapps.kache.FileKache
import com.mayakapps.kache.KacheStrategy
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class App(
    val context: Application,
    val settings: SharedPreferences,
) {
    val throwFlow = MutableSharedFlow<Throwable>()
    val messageFlow = MutableSharedFlow<Message>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Updated by PlayerService whenever player state changes; read at crash-record time.
    @Volatile var crashExtensionId: String = "none"
    @Volatile var crashPlayerState: Int = 1  // Player.STATE_IDLE
    @Volatile var crashIsPlaying: Boolean = false

    private suspend fun getCache() = FileKache(
        context.cacheDir.resolve("kache").toString(),
        50 * 1024 * 1024
    ) {
        strategy = KacheStrategy.LRU
    }

    val fileCache = scope.async(Dispatchers.IO, CoroutineStart.LAZY) {
        runCatching { getCache() }.getOrElse {
            context.cacheDir.resolve("kache").deleteRecursively()
            getCache()
        }
    }

    private val _networkFlow = MutableStateFlow(NetworkConnection.NotConnected)
    val networkFlow = _networkFlow.asStateFlow()
    val isUnmetered get() = networkFlow.value == NetworkConnection.Unmetered

    init {
        scope.launch {
            throwFlow.collectLatest {
                it.printStackTrace()
                // BuildConfig.HAS_FIREBASE is a compile-time boolean (no Firebase type referenced),
                // so in no-json builds this branch is dead and FirebaseCrashlytics is never loaded.
                if (BuildConfig.HAS_FIREBASE) FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("extension_id", crashExtensionId)
                    setCustomKey("player_state", crashPlayerState)
                    setCustomKey("is_playing", crashIsPlaying)
                    recordException(it)
                }
            }
        }
        // Network-state monitoring is best-effort. Some OEM/framework builds reject the ConnectivityManager
        // binder call from the system server (e.g. OnePlus 7 / GM1913 / Android 11 threw SecurityException/
        // RemoteException "Package android does not belong to <uid>"). Because this runs in the App singleton's
        // CONSTRUCTOR, that used to cascade through Koin (App -> ExtensionLoader) and crash app launch entirely.
        // Guard it so a flaky framework call degrades to "no live network updates" instead of failing to start.
        try {
            val connectivityManager =
                context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val isMetered = connectivityManager.isActiveNetworkMetered
                    _networkFlow.value = if (isMetered) NetworkConnection.Metered
                    else NetworkConnection.Unmetered
                }

                override fun onLost(network: Network) {
                    _networkFlow.value = NetworkConnection.NotConnected
                }
            }
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            _networkFlow.value = when {
                connectivityManager.activeNetwork == null -> NetworkConnection.NotConnected
                connectivityManager.isActiveNetworkMetered -> NetworkConnection.Metered
                else -> NetworkConnection.Unmetered
            }
        } catch (e: Exception) {
            // Degrade gracefully rather than crash construction: assume an online-but-metered connection so
            // extensions still treat the device as connected (NotConnected would make them behave as offline)
            // and playback stays on the conservative metered-quality path. No live updates on this device.
            e.printStackTrace()
            _networkFlow.value = NetworkConnection.Metered
            scope.launch { throwFlow.emit(e) }  // record non-fatally (same Crashlytics path as everywhere else)
        }
    }
}
