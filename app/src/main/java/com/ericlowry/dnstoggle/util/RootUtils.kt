package com.ericlowry.dnstoggle.util

import android.util.Log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.io.DataOutputStream
import java.io.IOException

object RootUtils {
	private const val TAG = "RootUtils"

	/**
	 * Checks if su binary is available in common paths.
	 */
	fun isAvailable(): Boolean {
		val paths = arrayOf(
			"/system/bin/su",
			"/system/xbin/su",
			"/sbin/su",
			"/system/sd/xbin/su",
			"/system/bin/failsafe/su",
			"/data/local/xbin/su",
			"/data/local/bin/su",
			"/data/local/su",
			"/su/bin/su"
		)
		for (path in paths) {
			if (java.io.File(path).exists()) return true
		}
		return false
	}

	/**
	 * Attempts to grant WRITE_SECURE_SETTINGS permission using root access.
	 * Returns true if the command was executed successfully.
	 */
	suspend fun grantSecureSettingsPermission(packageName: String): Boolean =
		withContext(Dispatchers.IO) {
			var process: Process? = null
			var os: DataOutputStream? = null
			try {
				process = Runtime.getRuntime().exec("su")
				os = DataOutputStream(process.outputStream)

				os.writeBytes("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n")
				os.writeBytes("exit\n")
				os.flush()

				process.waitFor() == 0
			} catch (e: Exception) {
				Log.e(TAG, "Failed to grant secure settings permission via root", e)
				false
			} finally {
				try {
					os?.close()
				} catch (e: IOException) {
					Log.w(TAG, "Failed to close output stream", e)
				}
				process?.destroy()
			}
		}
}
