package me.rerere.rikkahub.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * 分块并行下载器：
 * - 先用 HEAD 拿到文件总大小，支持服务端 Range 即可分块
 * - 默认 4 个连接并行下载，显著提升大文件（APK / GGUF 模型）下载速度
 * - 断点续传：已存在的分片文件跳过重下
 * - 进度回调为已下载字节数，调用方自行换算百分比
 */
object ChunkedDownloader {
    private const val DEFAULT_CHUNKS = 4
    private const val CHUNK_SUFFIX = ".part"
    private const val MIN_CHUNK_SIZE = 1L shl 20 // 小于 1MB 不分块

    suspend fun download(
        client: OkHttpClient,
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        chunks: Int = DEFAULT_CHUNKS,
        userAgent: String,
    ): File = withContext(Dispatchers.IO) {
        val total = probeSize(client, url, userAgent)
        // 目标已完整：幂等返回（中断重下时直接跳过）
        if (total > 0 && target.length() >= total) {
            onProgress(total, total)
            return@withContext target
        }
        if (total <= 0 || total < MIN_CHUNK_SIZE) {
            // 服务器不支持 Range 或文件太小：单连接断点续传
            downloadSingle(client, url, target, onProgress, userAgent)
        } else {
            val tmpDir = File(target.parentFile, "${target.name}.chunks").apply { mkdirs() }
            val partFiles = (0 until chunks).map { File(tmpDir, "$it$CHUNK_SUFFIX") }
            val downloaded = AtomicLong(partFiles.sumOf { it.length() })
            val ranges = (0 until chunks).map { i ->
                val start = total * i / chunks
                val end = if (i == chunks - 1) total - 1 else total * (i + 1) / chunks - 1
                start to end
            }
            coroutineScope {
                ranges.indices.map { i ->
                    async(Dispatchers.IO) {
                        val (start, end) = ranges[i]
                        val part = partFiles[i]
                        if (part.length() == (end - start + 1)) {
                            // 已有完整分片，跳过
                        } else {
                            // 断点续传：从当前已下长度继续
                            val resumeFrom = part.length()
                            val rangeStart = start + resumeFrom
                            if (rangeStart <= end) {
                                downloadChunk(
                                    client = client,
                                    url = url,
                                    start = rangeStart,
                                    end = end,
                                    part = part,
                                    userAgent = userAgent,
                                    onDelta = { added ->
                                        val current = downloaded.addAndGet(added)
                                        onProgress(current.coerceAtMost(total), total)
                                    },
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            // 合并分片
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(total)
                for (i in partFiles.indices) {
                    val part = partFiles[i]
                    if (!part.exists() || part.length() != (ranges[i].second - ranges[i].first + 1)) {
                        throw IllegalStateException("分片 $i 下载不完整")
                    }
                    part.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                        }
                    }
                    part.delete()
                }
            }
            onProgress(total, total)
            tmpDir.delete()
            target
        }
    }

    private fun probeSize(client: OkHttpClient, url: String, userAgent: String): Long =
        runCatching {
            client.newCall(
                Request.Builder().url(url).head().header("User-Agent", userAgent).build()
            ).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                } else {
                    -1L
                }
            }
        }.getOrDefault(-1L)

    private fun downloadSingle(
        client: OkHttpClient,
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
        userAgent: String,
    ): File {
        // 断点续传：文件已有部分长度时从该位置继续
        val resumeFrom = if (target.exists()) target.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body
            val total = resumeFrom + body.contentLength()
            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(resumeFrom)
                val buffer = ByteArray(256 * 1024)
                var downloaded = resumeFrom
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        return target
    }

    private fun downloadChunk(
        client: OkHttpClient,
        url: String,
        start: Long,
        end: Long,
        part: File,
        userAgent: String,
        onDelta: (Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code !in 200..299 && resp.code != 206) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body
            val offset = part.length()
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(256 * 1024)
                var downloaded = offset
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        onDelta(read.toLong())
                    }
                }
            }
        }
    }
}
