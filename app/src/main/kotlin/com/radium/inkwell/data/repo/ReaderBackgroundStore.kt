package com.radium.inkwell.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * 用户导入的阅读背景图片：放在 `filesDir/backgrounds/`，设置里只存文件名（见 ReaderSettings.backgroundImage）。
 *
 * **图片文件不进 WebDAV**，与字体同一条线（见 [ReaderFontStore]）：同步的只是文件名，
 * 另一台设备没有这个文件时背景不生效、退回纸色，备份导入也不会拿远端的名字覆盖本地。
 *
 * 导入时把长边压到 [MAX_DIMENSION] 以内：背景图最终只铺一屏，原图动辄四五千像素，
 * 解码出来一张就是上百 MB，翻页位图还要再拷一份 —— 压到 2560 以内肉眼无差，内存省一个量级。
 */
class ReaderBackgroundStore(context: Context) {

    private val resolver = context.contentResolver
    val dir: File = File(context.filesDir, BACKGROUNDS_DIR)

    private val importMutex = Mutex()

    /** 备份导入时查文件在不在本机；名字非法（带路径）一律算不在 */
    fun isAvailable(name: String): Boolean = resolve(name)?.isFile == true

    /** 名字 → 目录里的文件。只接受纯文件名，防同步来的名字带路径穿越出目录 */
    fun resolve(name: String): File? {
        if (name.isBlank() || File(name).name != name) return null
        val file = File(dir, name)
        return file.takeIf {
            runCatching { it.canonicalFile == File(dir.canonicalFile, name) }.getOrDefault(false)
        }
    }

    /**
     * 解码成阅读页用的位图。软件位图（ARGB_8888）：仿真翻页要把整页画进软件画布，
     * 硬件位图画不进去（见 ReaderImages 的同一条规矩）。文件不在或解不出返回 null，页面退回纸色。
     */
    fun loadBitmap(name: String): ImageBitmap? {
        val file = resolve(name) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            bitmap.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    /** 从 SAF 导入一张图片，返回存好的文件名。先落临时文件、验过能解码，再转正。 */
    suspend fun import(uri: Uri): Result<String> = importMutex.withLock {
        withContext(Dispatchers.IO) { readImage(uri) }
    }

    private suspend fun readImage(uri: Uri): Result<String> {
        var temp: File? = null
        return try {
            dir.mkdirs()
            val (displayName, size) = queryFileInfo(uri)
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext !in IMAGE_EXTENSIONS) error("只支持 JPG / PNG / WebP 图片")
            if (size != null && size > MAX_IMAGE_BYTES) throw imageTooLarge()
            // createTempFile 的前缀至少 3 个字符，"bg" 会直接抛 Prefix string too short
            val tmp = File.createTempFile("bg_", ".tmp", dir)
            temp = tmp
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> copyImageBytes(input, out, MAX_IMAGE_BYTES) }
            } ?: error("无法读取所选文件")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("这不是能用的图片文件")
            currentCoroutineContext().ensureActive()

            val base = sanitize(displayName.substringBeforeLast('.'))
            val dest = if (max(bounds.outWidth, bounds.outHeight) > MAX_DIMENSION) {
                // 太大：解码缩放后转存 JPEG。背景图不需要透明通道，JPEG 体积也小得多
                val target = uniqueFile(base, "jpg")
                val scaled = decodeScaled(tmp, bounds) ?: error("这不是能用的图片文件")
                try {
                    target.outputStream().use { out ->
                        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                            throw IOException("保存背景图片失败")
                        }
                    }
                } finally {
                    scaled.recycle()
                }
                tmp.delete()
                target
            } else {
                val target = uniqueFile(base, ext)
                if (!tmp.renameTo(target)) throw IOException("保存背景图片失败")
                target
            }
            temp = null
            Result.success(dest.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            temp?.delete()
        }
    }

    /** 按采样解码再精确缩到长边 [MAX_DIMENSION]；采样后已经够小就原样返回解码结果 */
    private fun decodeScaled(file: File, bounds: BitmapFactory.Options): Bitmap? {
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val longEdge = max(decoded.width, decoded.height)
        if (longEdge <= MAX_DIMENSION) return decoded
        val scale = MAX_DIMENSION.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    suspend fun delete(name: String) = importMutex.withLock {
        withContext(Dispatchers.IO) {
            resolve(name)?.let { if (it.exists() && !it.delete()) throw IOException("无法删除背景图片文件") }
        }
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Long?> =
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
            val byteCount = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
            (displayName ?: "background.jpg") to byteCount
        } ?: ("background.jpg" to null)

    /** 同名已存在就加序号：换一张同名图不该悄悄覆盖另一槽还在用的那张 */
    private fun uniqueFile(base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n).$ext").also { n++ }
        return f
    }

    private companion object {
        const val BACKGROUNDS_DIR = "backgrounds"
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        const val MAX_DIMENSION = 2560
        const val JPEG_QUALITY = 90

        /** 文件名会进设置并同步出去：去掉路径分隔与控制字符，别的原样保留 */
        fun sanitize(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "背景图" }.take(60)
    }
}

/** 最多写入 limit 字节，越界立刻报错，不等复制完一个大文件才发现超限（与字体导入同一套路） */
private suspend fun copyImageBytes(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val remaining = limit - copied
        val read = input.read(buffer, 0, if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt())
        if (read < 0) return
        if (read.toLong() > remaining) throw imageTooLarge()
        output.write(buffer, 0, read)
        copied += read
    }
}

private fun imageTooLarge() =
    IOException("图片文件超过 32 MiB 导入上限，请选择较小的图片。")

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
