package com.ericlowry.dnstoggle

import java.io.DataOutputStream
import java.io.IOException

object RootUtils {
    /**
     * Attempts to grant WRITE_SECURE_SETTINGS permission using root access.
     * Returns true if the command was executed successfully.
     */
    fun grantSecureSettingsPermission(packageName: String): Boolean {
        var process: Process? = null
        var os: DataOutputStream? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            
            os.writeBytes("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n")
            os.writeBytes("exit\n")
            os.flush()
            
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        } finally {
            try {
                os?.close()
            } catch (_: IOException) {
                // ignore
            }
            process?.destroy()
        }
    }
}
