package com.vk062777.pdfsmith.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain, local record of what the app has done and when.
 *
 * It lives in the app's own private storage, is never uploaded anywhere, and is
 * capped so it cannot grow without bound. Passwords and document contents are
 * never written here - only the task name, the file it produced, and the outcome.
 */
object AppLog {

    private const val FILE_NAME = "kavopdf-activity.log"
    private const val MAX_BYTES = 512 * 1024
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    data class Entry(val time: Long, val ok: Boolean, val action: String, val detail: String)

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun record(ctx: Context, action: String, detail: String = "", ok: Boolean = true) {
        synchronized(lock) {
            try {
                val f = file(ctx)
                // keep the file bounded: when it gets big, drop the older half
                if (f.length() > MAX_BYTES) {
                    val lines = f.readLines()
                    f.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
                }
                val line = buildString {
                    append(System.currentTimeMillis()).append('\t')
                    append(if (ok) "OK" else "FAIL").append('\t')
                    append(clean(action)).append('\t')
                    append(clean(detail))
                }
                f.appendText(line + "\n")
            } catch (_: Exception) {
                // logging must never break a task
            }
        }
    }

    fun entries(ctx: Context): List<Entry> = try {
        file(ctx).readLines().mapNotNull { raw ->
            val p = raw.split('\t')
            if (p.size < 4) null
            else Entry(p[0].toLongOrNull() ?: 0L, p[1] == "OK", p[2], p[3])
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun clear(ctx: Context) {
        synchronized(lock) { runCatching { file(ctx).delete() } }
    }

    /** One readable line, used by the screen and the exported PDF. */
    fun format(e: Entry): String =
        "${stamp.format(Date(e.time))}   ${if (e.ok) "OK  " else "FAIL"}   ${e.action}" +
            (if (e.detail.isNotEmpty()) "   $e.detail" else "")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').take(300)
}
