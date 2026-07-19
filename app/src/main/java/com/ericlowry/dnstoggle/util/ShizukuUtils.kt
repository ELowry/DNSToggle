package com.ericlowry.dnstoggle.util

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.ericlowry.dnstoggle.BuildConfig
import com.ericlowry.dnstoggle.shizuku.IShizukuUserService
import com.ericlowry.dnstoggle.shizuku.ShizukuUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

object ShizukuUtils {
	private const val TAG = "ShizukuUtils"
	private const val PERMISSION_REQUEST_CODE = 12277
	private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
		Shizuku.UserServiceArgs(
			ComponentName(
				context.packageName,
				ShizukuUserService::class.java.name
			)
		)
			.daemon(false)
			.processNameSuffix("shizuku")
			.debuggable(BuildConfig.DEBUG)
			.version(BuildConfig.VERSION_CODE)

	fun isAvailable(): Boolean {
		return try {
			Shizuku.pingBinder() && !Shizuku.isPreV11()
		} catch (e: Throwable) {
			false
		}
	}

	fun hasPermission(): Boolean {
		return try {
			Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
		} catch (e: Throwable) {
			false
		}
	}

	private suspend fun requestPermission(): Boolean {
		if (hasPermission()) return true
		return suspendCancellableCoroutine { cont ->
			val listener = object : Shizuku.OnRequestPermissionResultListener {
				override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
					if (requestCode != PERMISSION_REQUEST_CODE) return
					Shizuku.removeRequestPermissionResultListener(this)
					if (cont.isActive) {
						cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
					}
				}
			}
			Shizuku.addRequestPermissionResultListener(listener)
			cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
			try {
				Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
			} catch (e: Throwable) {
				Shizuku.removeRequestPermissionResultListener(listener)
				if (cont.isActive) cont.resume(false)
			}
		}
	}

	suspend fun grantSecureSettingsPermission(context: Context, packageName: String): Boolean {
		if (!isAvailable()) return false
		if (!requestPermission()) return false

		val args = userServiceArgs(context)
		return suspendCancellableCoroutine { cont ->
			lateinit var connection: ServiceConnection
			connection = object : ServiceConnection {
				override fun onServiceConnected(name: ComponentName, binder: IBinder) {
					ioScope.launch {
						val granted = try {
							if (binder.pingBinder()) {
								IShizukuUserService.Stub.asInterface(binder)
									.grantWriteSecureSettings(packageName)
							} else {
								false
							}
						} catch (e: Exception) {
							Log.e(TAG, "Failed to grant secure settings permission via Shizuku", e)
							false
						} finally {
							Shizuku.unbindUserService(args, connection, true)
						}
						if (cont.isActive) cont.resume(granted)
					}
				}

				override fun onServiceDisconnected(name: ComponentName) {}
			}
			cont.invokeOnCancellation {
				try {
					Shizuku.unbindUserService(args, connection, true)
				} catch (_: Throwable) {
				}
			}
			try {
				Shizuku.bindUserService(args, connection)
			} catch (e: Throwable) {
				Log.e(TAG, "Failed to bind Shizuku user service", e)
				if (cont.isActive) cont.resume(false)
			}
		}
	}
}

suspend fun attemptSecureSettingsGrant(context: Context, packageName: String): Boolean {
	if (ShizukuUtils.isAvailable() && ShizukuUtils.grantSecureSettingsPermission(
			context,
			packageName
		)
	) {
		return true
	}
	// Always try root as the final fallback, even if detection failed (handles hidden root)
	return RootUtils.grantSecureSettingsPermission(packageName)
}
