package me.rerere.rikkahub.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用内原生日志：全局内存环形缓冲，供设置页「日志」查看、复制、排查问题。
 */
object AppLogger {
    data class Entry(
        val time: String,
        val level: String,
        val tag: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 800
    private val buffer = CopyOnWriteArrayList<Entry>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, message: String) = add("INFO", tag, message)
    fun w(tag: String, message: String) = add("WARN", tag, message)
    fun e(tag: String, message: String) = add("ERROR", tag, message)

    private fun add(level: String, tag: String, message: String) {
        val entry = Entry(fmt.format(Date()), level, tag, message)
        buffer.add(entry)
        while (buffer.size > MAX_ENTRIES) {
            buffer.removeAt(0)
        }
    }

    fun entries(): List<Entry> = buffer.toList()

    fun snapshotText(): String {
        val sb = StringBuilder()
        buffer.forEach { e ->
            sb.append("[")
                .append(e.time)
                .append("] [")
                .append(e.level)
                .append("] [")
                .append(e.tag)
                .append("] ")
                .append(e.message)
                .append("\n")
        }
        return sb.toString()
    }

    fun clear() = buffer.clear()
}
