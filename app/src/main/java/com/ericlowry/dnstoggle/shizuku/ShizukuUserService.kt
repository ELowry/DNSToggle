package com.ericlowry.dnstoggle.shizuku

import com.ericlowry.dnstoggle.data.Constants

class ShizukuUserService : IShizukuUserService.Stub() {

	override fun destroy() {
		kotlin.system.exitProcess(0)
	}

	override fun grantWriteSecureSettings(): Boolean {
		return try {
			val process = Runtime.getRuntime()
				.exec(
					arrayOf(
						"pm",
						"grant",
						com.ericlowry.dnstoggle.BuildConfig.APPLICATION_ID,
						Constants.PERMISSION_WRITE_SECURE_SETTINGS
					)
				)
			process.waitFor() == 0
		} catch (_: Exception) {
			false
		}
	}
}
