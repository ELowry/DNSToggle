package com.ericlowry.dnstoggle.shizuku

class ShizukuUserService : IShizukuUserService.Stub() {

	override fun destroy() {
		kotlin.system.exitProcess(0)
	}

	override fun grantWriteSecureSettings(packageName: String): Boolean {
		return try {
			val process = Runtime.getRuntime()
				.exec(
					arrayOf(
						"pm",
						"grant",
						packageName,
						"android.permission.WRITE_SECURE_SETTINGS"
					)
				)
			process.waitFor() == 0
		} catch (_: Exception) {
			false
		}
	}
}
