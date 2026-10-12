package de.bmclink

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Kleine Hilfe für Befehle mit Root-Rechten (Magisk/KernelSU/SuperSU). */
object Root {

    data class Result(val code: Int, val out: String)

    @Volatile
    private var cached: Boolean? = null

    fun available(force: Boolean = false): Boolean {
        if (!force) cached?.let { return it }
        val ok = try {
            run("id", timeoutSec = 15).out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
        cached = ok
        return ok
    }

    /** Führt ein Shell-Skript als root aus und liefert Exit-Code + Ausgabe (stdout+stderr). */
    fun run(script: String, timeoutSec: Long = 20): Result {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { w ->
            w.write(script)
            w.write("\nexit\n")
            w.flush()
        }
        val sb = StringBuilder()
        val reader = Thread {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).forEachLine { sb.appendLine(it) }
            } catch (_: Exception) {
            }
        }
        reader.start()
        val done = waitFor(p, timeoutSec)
        if (!done) p.destroy()
        reader.join(1000)
        return Result(if (done) p.exitValue() else -1, sb.toString().trim())
    }

    /** Startet einen dauerhaft laufenden Root-Prozess (z. B. DHCP-Server). */
    fun startLongRunning(script: String): Process {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        val w = p.outputStream.bufferedWriter()
        w.write(script)
        w.write("\n")
        w.flush()
        // stdin bleibt offen: der Kindprozess beendet sich, sobald wir den Prozess zerstören.
        return p
    }

    private fun waitFor(p: Process, timeoutSec: Long): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 26) {
            p.waitFor(timeoutSec, TimeUnit.SECONDS)
        } else {
            val end = System.currentTimeMillis() + timeoutSec * 1000
            while (System.currentTimeMillis() < end) {
                try {
                    p.exitValue()
                    return true
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(100)
                }
            }
            false
        }
    }
}
