package com.altivane.app.domain

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量运行日志：内存环形缓冲 + 文件追加（externalCacheDir/logs/app.log）。
 * 设置页可导出分享；崩溃时由 installCrashCapture 抓堆栈写入 crash.txt。
 */
object AppLog {

    private const val MAX_MEM = 600
    private const val MAX_FILE_BYTES = 256L * 1024L

    private val mem = ArrayDeque<String>()
    private var dir: File? = null
    private var ctxRef: Context? = null
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    @Synchronized
    fun init(ctx: Context) {
        ctxRef = ctx.applicationContext
        // externalCacheDir 极端场景（存储不可用/用户模式限制）可能为 null，日志降级仅内存
        val cache = ctx.applicationContext.externalCacheDir ?: return
        dir = File(cache, "logs").apply { mkdirs() }
    }

    @Synchronized
    fun i(tag: String, msg: String) = write("I", tag, msg)

    @Synchronized
    fun w(tag: String, msg: String) = write("W", tag, msg)

    @Synchronized
    fun e(tag: String, msg: String, err: Throwable? = null) =
        write("E", tag, msg + (err?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    private fun write(level: String, tag: String, msg: String) {
        val line = "${ts.format(Date())} $level/$tag: $msg"
        mem.addLast(line)
        while (mem.size > MAX_MEM) mem.removeFirst()
        android.util.Log.println(
            when (level) {
                "E" -> android.util.Log.ERROR
                "W" -> android.util.Log.WARN
                else -> android.util.Log.INFO
            }, "Altivane.$tag", msg
        )
        try {
            val d = dir ?: return
            val f = File(d, "app.log")
            if (f.length() > MAX_FILE_BYTES) f.renameTo(File(d, "app.old.log"))
            f.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    /** 内存快照（设置页快速查看） */
    @Synchronized
    fun snapshot(): List<String> = mem.toList()

    /** 日志文件 */
    fun file(): File? = dir?.let { File(it, "app.log") }?.takeIf { it.exists() }

    /** 崩溃文件（若存在） */
    fun crashFile(): File? = dir?.let { File(it, "crash.txt") }?.takeIf { it.exists() }

    @Synchronized
    fun clear() {
        mem.clear()
        dir?.let { d ->
            listOf("app.log", "app.old.log", "crash.txt").forEach {
                try { File(d, it).delete() } catch (_: Exception) {}
            }
        }
    }

    /** 全局崩溃捕获：堆栈 + 崩溃前最后 100 条日志写入 crash.txt，然后交还默认 handler（正常退出） */
    fun installCrashCapture(ctx: Context) {
        init(ctx)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val d = dir ?: return@runCatching
                val sb = StringBuilder()
                sb.appendLine("=== Altivane crash ${ts.format(Date())} thread=${t.name} ===")
                sb.appendLine(android.util.Log.getStackTraceString(e))
                sb.appendLine("--- last 100 log lines ---")
                snapshot().takeLast(100).forEach { sb.appendLine(it) }
                File(d, "crash.txt").writeText(sb.toString())
            }
            prev?.uncaughtException(t, e)
        }
        i("App", "crash capture installed")
    }
}
