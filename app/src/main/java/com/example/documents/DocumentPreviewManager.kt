package com.example.documents

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import com.example.webview.WebHostPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface DocumentPreviewState {
    data object Hidden : DocumentPreviewState
    data class Loading(val fileName: String) : DocumentPreviewState
    data class Pdf(val file: File, val fileName: String, val mimeType: String) : DocumentPreviewState
    data class Image(val file: File, val fileName: String, val mimeType: String) : DocumentPreviewState
    data class Text(
        val content: String,
        val file: File,
        val fileName: String,
        val mimeType: String,
    ) : DocumentPreviewState
    data class ExternalReady(
        val file: File,
        val fileName: String,
        val mimeType: String,
        val launchedExternally: Boolean,
    ) : DocumentPreviewState
    data class Error(val message: String) : DocumentPreviewState
}

class DocumentPreviewManager(private val appContext: Context) {

    private val _state = MutableStateFlow<DocumentPreviewState>(DocumentPreviewState.Hidden)
    val state: StateFlow<DocumentPreviewState> = _state.asStateFlow()

    fun dismiss() {
        _state.value = DocumentPreviewState.Hidden
    }

    suspend fun previewHttpsDocument(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (!WebHostPolicy.isSafeHttpsUrl(url)) {
            _state.value = DocumentPreviewState.Error("URL dokumen tidak didukung.")
            return
        }

        val initialGuessName = guessSafeFileName(url, contentDisposition, mimeType)
        _state.value = DocumentPreviewState.Loading(initialGuessName)

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    requestMethod = "GET"
                    if (!userAgent.isNullOrBlank()) {
                        setRequestProperty("User-Agent", userAgent)
                    }
                    // Only send WebView session cookies to allowlisted in-app hosts
                    if (WebHostPolicy.shouldKeepInApp(url)) {
                        val cookies = CookieManager.getInstance().getCookie(url)
                        if (!cookies.isNullOrBlank()) {
                            setRequestProperty("Cookie", cookies)
                        }
                    }
                }

                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code")
                    }

                    val headerMime = connection.contentType?.substringBefore(';')?.trim()
                    val resolvedMime = resolveMimeType(
                        providedMime = headerMime ?: mimeType,
                        fileName = initialGuessName,
                    )
                    val finalDisposition = connection.getHeaderField("Content-Disposition") ?: contentDisposition
                    val finalName = guessSafeFileName(url, finalDisposition, resolvedMime)

                    val bytes = readCappedBytes(connection.inputStream, MAX_DOCUMENT_BYTES)
                    val savedFile = writeToScopedCache(finalName, bytes)
                    Triple(savedFile, finalName, resolvedMime)
                } finally {
                    connection.disconnect()
                }
            }
        }

        result.onSuccess { (file, name, resolvedMime) ->
            presentDownloadedFile(file, name, resolvedMime)
        }.onFailure { err ->
            _state.value = DocumentPreviewState.Error(
                err.localizedMessage ?: "Gagal mengunduh dokumen.",
            )
        }
    }

    suspend fun previewDataOrBlobDocument(
        base64DataUrl: String,
        mimeType: String?,
        fileName: String?,
    ) {
        val cleaned = base64DataUrl.trim()
        val parsedMime = if (cleaned.startsWith("data:", ignoreCase = true)) {
            cleaned.substringAfter("data:").substringBefore(';').substringBefore(',')
        } else {
            mimeType.orEmpty()
        }
        val resolvedMime = resolveMimeType(parsedMime.ifBlank { mimeType }, fileName)
        val safeName = ensureExtension(
            sanitizeFileName(fileName?.ifBlank { null } ?: "dokumen"),
            resolvedMime,
        )

        _state.value = DocumentPreviewState.Loading(safeName)

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val base64Payload = if (cleaned.startsWith("data:", ignoreCase = true)) {
                    cleaned.substringAfter("base64,", "")
                } else {
                    cleaned
                }
                if (base64Payload.isBlank()) {
                    throw IllegalArgumentException("Data dokumen kosong.")
                }
                // Quick upper bound check before decoding
                if (base64Payload.length > (MAX_DOCUMENT_BYTES * 4L / 3L) + 1024L) {
                    throw IllegalArgumentException("Ukuran dokumen melebihi batas 25 MB.")
                }
                val bytes = Base64.decode(base64Payload, Base64.DEFAULT)
                if (bytes.size > MAX_DOCUMENT_BYTES) {
                    throw IllegalArgumentException("Ukuran dokumen melebihi batas 25 MB.")
                }
                val savedFile = writeToScopedCache(safeName, bytes)
                Triple(savedFile, safeName, resolvedMime)
            }
        }

        result.onSuccess { (file, name, finalMime) ->
            presentDownloadedFile(file, name, finalMime)
        }.onFailure { err ->
            _state.value = DocumentPreviewState.Error(
                err.localizedMessage ?: "Gagal memproses pratinjau dokumen.",
            )
        }
    }

    private suspend fun presentDownloadedFile(
        file: File,
        fileName: String,
        mimeType: String,
    ) {
        val lowerMime = mimeType.lowercase()
        val lowerName = fileName.lowercase()

        when {
            lowerMime == "application/pdf" || lowerName.endsWith(".pdf") -> {
                _state.value = DocumentPreviewState.Pdf(file, fileName, "application/pdf")
            }

            (lowerMime.startsWith("image/") && lowerMime != "image/svg+xml") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") ||
                lowerName.endsWith(".gif") -> {
                _state.value = DocumentPreviewState.Image(file, fileName, mimeType)
            }

            isTextDocument(lowerMime, lowerName) -> {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        file.readBytes()
                            .take(MAX_TEXT_PREVIEW_BYTES)
                            .toByteArray()
                            .toString(Charsets.UTF_8)
                    }.getOrDefault("")
                }
                _state.value = DocumentPreviewState.Text(
                    content = text,
                    file = file,
                    fileName = fileName,
                    mimeType = mimeType,
                )
            }

            else -> {
                val launched = openExternally(appContext, file, mimeType)
                _state.value = DocumentPreviewState.ExternalReady(
                    file = file,
                    fileName = fileName,
                    mimeType = mimeType,
                    launchedExternally = launched,
                )
            }
        }
    }

    fun openExternally(context: Context, file: File, mimeType: String): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun shareFile(context: Context, file: File, mimeType: String): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, file.name).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    suspend fun saveFileToUri(destinationUri: android.net.Uri, sourceFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val outStream = appContext.contentResolver.openOutputStream(destinationUri)
                    ?: return@runCatching false
                outStream.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                    output.flush()
                }
                true
            }.getOrDefault(false)
        }
    }

    private fun writeToScopedCache(fileName: String, bytes: ByteArray): File {
        val dir = File(appContext.cacheDir, PREVIEW_SUBDIR).apply { mkdirs() }
        pruneOldCacheFiles(dir)

        val safeName = "${System.currentTimeMillis()}_${sanitizeFileName(fileName)}"
        val target = File(dir, safeName).canonicalFile
        val basePath = dir.canonicalPath + File.separator
        if (!target.path.startsWith(basePath)) {
            throw SecurityException("Nama file tidak valid.")
        }

        FileOutputStream(target).use { out ->
            out.write(bytes)
        }
        return target
    }

    private fun pruneOldCacheFiles(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED_FILES).forEach { runCatching { it.delete() } }
    }

    private fun readCappedBytes(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    throw IllegalArgumentException("Ukuran dokumen melebihi batas 25 MB.")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun guessSafeFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        val guessed = runCatching {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }.getOrDefault("document")
        return ensureExtension(sanitizeFileName(guessed), mimeType)
    }

    private fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        return base
            .replace(UNSAFE_FILENAME_REGEX, "_")
            .trim('.', '_')
            .take(80)
            .ifBlank { "document" }
    }

    private fun resolveMimeType(providedMime: String?, fileName: String?): String {
        val clean = providedMime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (clean.isNotBlank() && clean != "application/octet-stream") {
            return clean
        }
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val fromExt = if (ext.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        } else {
            null
        }
        return fromExt ?: clean.ifBlank { "application/octet-stream" }
    }

    private fun ensureExtension(name: String, mimeType: String?): String {
        if (name.contains('.') && name.substringAfterLast('.').length in 1..6) {
            return name
        }
        val ext = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            "text/markdown" -> "md"
            "text/csv" -> "csv"
            "application/json" -> "json"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        }
        return if (!ext.isNullOrBlank()) "$name.$ext" else name
    }

    private fun isTextDocument(mime: String, lowerName: String): Boolean {
        return mime.startsWith("text/") && mime != "text/html" ||
            mime == "application/json" ||
            mime == "application/xml" ||
            lowerName.endsWith(".txt") ||
            lowerName.endsWith(".md") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".csv") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".log") ||
            lowerName.endsWith(".kt") ||
            lowerName.endsWith(".js") ||
            lowerName.endsWith(".ts") ||
            lowerName.endsWith(".py")
    }

    companion object {
        private const val PREVIEW_SUBDIR = "preview_docs"
        private const val MAX_DOCUMENT_BYTES = 25 * 1024 * 1024 // 25 MB
        private const val MAX_TEXT_PREVIEW_BYTES = 512 * 1024 // 512 KB for smooth text rendering
        private const val MAX_CACHED_FILES = 10
        private val UNSAFE_FILENAME_REGEX = Regex("[^a-zA-Z0-9._-]")
    }
}
