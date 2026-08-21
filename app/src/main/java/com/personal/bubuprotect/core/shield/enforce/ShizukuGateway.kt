package com.personal.bubuprotect.core.shield.enforce

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.InputStream

/**
 * Runs shell commands at Shizuku's privilege, when the user has chosen to provide it.
 *
 * ### What this is and is not
 *
 * Shizuku is a separate app the user installs and starts themselves, either over wireless debugging or
 * with root. It holds shell UID and hands out a binder. Nothing here escalates anything: this class is
 * a *client*, and on a phone where the user never set Shizuku up every call returns
 * [Availability.NotInstalled] and the shield stays at its `ADVISE` tier.
 *
 * That is the honest trade being offered. Removing preinstalled adware, or revoking an app's overlay
 * capability without uninstalling it, are things Android does not let an ordinary app do - and the only
 * routes to them are root, an enterprise enrollment that needs a factory reset, or this. Shizuku is the
 * one that costs the user nothing permanent.
 *
 * ### Why the process call goes through reflection
 *
 * `Shizuku.newProcess` is the API that runs a command, and it is marked restricted in the library - it
 * is not part of the supported surface and its signature has moved between releases. Calling it
 * reflectively means a Shizuku update that changes or removes it produces
 * [Result.Unsupported] at runtime instead of a `NoSuchMethodError` that takes the process down. A
 * remediation feature failing to a message is acceptable; a security app crashing because a helper it
 * does not control changed a method signature is not.
 */
class ShizukuGateway {

    sealed interface Availability {

        /** Shizuku is not on the device. The overwhelmingly common case. */
        data object NotInstalled : Availability

        /** Installed, but the service is not running - the user has to start it after each reboot. */
        data object NotRunning : Availability

        /** Running, but this app has not been granted access yet. */
        data object PermissionRequired : Availability

        data object Ready : Availability
    }

    sealed interface Result {

        /** @param output combined stdout and stderr, for surfacing a failure the user can report. */
        data class Success(val output: String) : Result

        /** The command ran and returned non-zero. */
        data class Failed(val exitCode: Int, val output: String) : Result

        /** Shizuku is absent, not running, or not granted. Nothing was attempted. */
        data class NotAvailable(val availability: Availability) : Result

        /** The Shizuku API on this device does not expose a way to run commands. */
        data class Unsupported(val why: String) : Result
    }

    fun availability(): Availability = try {
        when {
            !Shizuku.pingBinder() -> Availability.NotRunning
            Shizuku.isPreV11() -> Availability.NotInstalled
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                Availability.Ready
            else -> Availability.PermissionRequired
        }
    } catch (_: Exception) {
        // The API throws rather than returning false when the provider was never installed.
        Availability.NotInstalled
    }

    /**
     * Asks Shizuku for access.
     *
     * Fire-and-forget: the answer arrives in Shizuku's own dialog and the result is read by calling
     * [availability] again when the user comes back. There is deliberately no callback plumbing, because
     * the shield screen re-checks availability on resume anyway - which is also the moment the user has
     * just returned from granting it.
     */
    fun requestPermission() {
        try {
            if (availability() == Availability.PermissionRequired) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (_: Exception) {
            // Nothing to do: the next availability() call reports the real state.
        }
    }

    /**
     * Runs one command and waits for it.
     *
     * @param command argv, already split. Not a shell string - there is no shell here, so nothing is
     *   word-split or glob-expanded and a package name cannot become two arguments or an injection.
     *   Every caller in [RemediationLadder] passes a fixed verb plus one package name.
     */
    suspend fun exec(vararg command: String): Result = withContext(Dispatchers.IO) {
        val availability = availability()
        if (availability != Availability.Ready) return@withContext Result.NotAvailable(availability)

        try {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = newProcess.invoke(null, arrayOf(*command), null, null)
                ?: return@withContext Result.Unsupported("Shizuku returned no process")

            val type = process.javaClass
            val stdout = readAll(type.getMethod("getInputStream").invoke(process) as? InputStream)
            val stderr = readAll(type.getMethod("getErrorStream").invoke(process) as? InputStream)
            val exit = type.getMethod("waitFor").invoke(process) as? Int ?: -1

            val output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n").trim()

            if (exit == 0) Result.Success(output) else Result.Failed(exit, output)
        } catch (e: NoSuchMethodException) {
            Result.Unsupported("This version of Shizuku does not expose command execution")
        } catch (e: Exception) {
            Result.Unsupported(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readAll(stream: InputStream?): String =
        stream?.use { it.bufferedReader().readText() }.orEmpty()

    private companion object {
        const val PERMISSION_REQUEST_CODE = 8721
    }
}
