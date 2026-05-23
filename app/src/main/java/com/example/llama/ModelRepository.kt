package com.example.llama

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ModelRepository(private val context: Context) {
    private val contentResolver: ContentResolver = context.contentResolver

    val modelsDir: File
        get() = File(context.filesDir, DIRECTORY_MODELS).also { dir ->
            if (dir.exists() && !dir.isDirectory) dir.delete()
            if (!dir.exists()) dir.mkdirs()
        }

    suspend fun importModel(uri: Uri): File = withContext(Dispatchers.IO) {
        val fileName = displayName(uri).sanitizeFileName().ensureGgufExtension()
        val target = File(modelsDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: error("Cannot open selected file")
        target
    }

    suspend fun downloadFromHuggingFace(
        repo: String,
        filename: String,
        baseUrl: String,
        token: String?,
        onProgress: suspend (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(repo.isNotBlank()) { "Hugging Face repo is required" }
        require(filename.isNotBlank()) { "GGUF filename is required" }

        val target = File(modelsDir, filename.substringAfterLast('/').sanitizeFileName().ensureGgufExtension())
        val connection = URL(huggingFaceResolveUrl(baseUrl, repo, filename)).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "MyLlama-Android")
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("Hugging Face download failed: HTTP $code $errorText")
            }

            val contentLength = connection.contentLengthLong
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (contentLength > 0) {
                            onProgress(((copied * 100) / contentLength).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            onProgress(100)
            target
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    suspend fun listHuggingFaceModels(
        query: String?,
        sort: HuggingFaceSort,
        baseUrl: String,
        token: String?
    ): List<HuggingFaceModel> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "filter" to "gguf",
            "sort" to sort.apiValue,
            "direction" to "-1",
            "limit" to "50",
            "full" to "true"
        )
        if (!query.isNullOrBlank()) {
            params["search"] = query.trim()
        }

        val url = "${huggingFaceBaseUrl(baseUrl)}/api/models?" + params.entries.joinToString("&") {
            "${it.key.urlPathEncode()}=${it.value.urlPathEncode()}"
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "MyLlama-Android")
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("Hugging Face model list failed: HTTP $code $errorText")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            buildList {
                for (index in 0 until array.length()) {
                    val model = array.getJSONObject(index)
                    val repoId = model.optString("modelId", model.optString("id"))
                    if (repoId.isBlank()) continue
                    if (!model.looksLikeTextModel()) continue

                    val siblings = model.optJSONArray("siblings") ?: JSONArray()
                    val siblingFiles = siblings.toGgufFiles()
                    val ggufFiles = runCatching {
                        listRepoGgufFiles(
                            baseUrl = baseUrl,
                            repoId = repoId,
                            token = token
                        ).takeIf { it.isNotEmpty() } ?: siblingFiles
                    }.getOrElse {
                        siblingFiles
                    }.sortedByQuality()
                    if (ggufFiles.isEmpty()) continue

                    add(
                        HuggingFaceModel(
                            repoId = repoId,
                            files = ggufFiles,
                            downloads = model.optLong("downloads", 0L),
                            likes = model.optLong("likes", 0L),
                            trendingScore = model.optLong("trendingScore", 0L),
                            lastModified = model.optString("lastModified"),
                            gated = model.isGated(),
                            pipelineTag = model.optString("pipeline_tag")
                        )
                    )
                    if (size >= MODEL_RESULT_LIMIT) break
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "model.gguf"
    }

    private fun huggingFaceResolveUrl(baseUrl: String, repo: String, filename: String): String {
        val encodedRepo = repo.trim().split('/').joinToString("/") { it.urlPathEncode() }
        val encodedFile = filename.trim().split('/').joinToString("/") { it.urlPathEncode() }
        return "${huggingFaceBaseUrl(baseUrl)}/$encodedRepo/resolve/main/$encodedFile?download=true"
    }

    private fun listRepoGgufFiles(baseUrl: String, repoId: String, token: String?): List<HuggingFaceFile> {
        val encodedRepo = repoId.trim().split('/').joinToString("/") { it.urlPathEncode() }
        val url = "${huggingFaceBaseUrl(baseUrl)}/api/models/$encodedRepo/tree/main?recursive=1"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "MyLlama-Android")
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("Hugging Face file list failed: HTTP $code $errorText")
            }
            return JSONArray(connection.inputStream.bufferedReader().use { it.readText() }).toTreeGgufFiles()
        } finally {
            connection.disconnect()
        }
    }

    private fun huggingFaceBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_HUGGING_FACE_BASE_URL }

    private fun String.urlPathEncode() =
        URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    private fun String.sanitizeFileName() =
        replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "model.gguf" }

    private fun String.ensureGgufExtension() =
        if (endsWith(".gguf", ignoreCase = true)) this else "$this.gguf"

    private fun org.json.JSONObject.looksLikeTextModel(): Boolean {
        val pipeline = optString("pipeline_tag").lowercase()
        if (pipeline in TEXT_PIPELINES) return true

        val tags = optJSONArray("tags") ?: return false
        for (index in 0 until tags.length()) {
            if (tags.optString(index).lowercase() in TEXT_TAGS) return true
        }
        return false
    }

    private fun org.json.JSONObject.isGated(): Boolean =
        when (val value = opt("gated")) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true) || value.equals("auto", ignoreCase = true)
            else -> false
        }

    private fun JSONArray.toGgufFiles(): List<HuggingFaceFile> {
        val files = mutableListOf<HuggingFaceFile>()
        for (index in 0 until length()) {
            val name = optJSONObject(index)?.optString("rfilename").orEmpty()
            val lower = name.lowercase()
            if (!lower.endsWith(".gguf")) continue
            if (lower.contains("mmproj")) continue
            if (lower.contains("imatrix")) continue
            if (Regex("""-\d{5}-of-\d{5}\.gguf$""").containsMatchIn(lower)) continue
            files.add(HuggingFaceFile(name, name.quantizationLabel(), UNKNOWN_SIZE_BYTES))
        }
        return files
    }

    private fun JSONArray.toTreeGgufFiles(): List<HuggingFaceFile> {
        val files = mutableListOf<HuggingFaceFile>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val name = item.optString("path")
            val lower = name.lowercase()
            if (!lower.endsWith(".gguf")) continue
            if (lower.contains("mmproj")) continue
            if (lower.contains("imatrix")) continue
            if (Regex("""-\d{5}-of-\d{5}\.gguf$""").containsMatchIn(lower)) continue
            val size = item.optLong("size", UNKNOWN_SIZE_BYTES)
            files.add(HuggingFaceFile(name, name.quantizationLabel(), size))
        }
        return files
    }

    private fun List<HuggingFaceFile>.sortedByQuality(): List<HuggingFaceFile> =
        sortedWith(
            compareBy<HuggingFaceFile> { file ->
                val lower = file.filename.lowercase()
                GGUF_PRIORITY.indexOfFirst { lower.contains(it) }.takeIf { it >= 0 } ?: GGUF_PRIORITY.size
            }.thenBy { it.filename.length }
        )

    private fun String.quantizationLabel(): String {
        val name = substringAfterLast('/').substringBeforeLast(".gguf").uppercase()
        val match = QUANTIZATION_REGEX.find(name)
        return match?.value ?: "GGUF"
    }

    private companion object {
        private const val DIRECTORY_MODELS = "models"
        private const val DEFAULT_BUFFER_SIZE = 1024 * 1024
        private const val MODEL_RESULT_LIMIT = 8
        private const val DEFAULT_HUGGING_FACE_BASE_URL = "https://huggingface.co"
        private const val UNKNOWN_SIZE_BYTES = -1L

        private val TEXT_PIPELINES = setOf(
            "text-generation",
            "text2text-generation",
            "image-text-to-text",
            "conversational",
            "question-answering"
        )
        private val TEXT_TAGS = setOf(
            "text-generation",
            "text2text-generation",
            "conversational",
            "llama",
            "qwen",
            "mistral",
            "gemma",
            "phi",
            "deepseek"
        )
        private val GGUF_PRIORITY = listOf(
            "q4_k_m",
            "q4_k_s",
            "iq4_nl",
            "q5_k_m",
            "q3_k_m",
            "q4_0",
            "q5_k_s",
            "q6_k",
            "q8_0",
            "iq4_xs",
            "q2_k"
        )
        private val QUANTIZATION_REGEX = Regex("""(?:I?Q\d(?:_[A-Z0-9]+)*|F16|F32)""")
    }
}

enum class HuggingFaceSort(val apiValue: String) {
    HOT("trendingScore"),
    LATEST("lastModified"),
    SEARCH("downloads")
}

data class HuggingFaceModel(
    val repoId: String,
    val files: List<HuggingFaceFile>,
    val downloads: Long,
    val likes: Long,
    val trendingScore: Long,
    val lastModified: String,
    val gated: Boolean,
    val pipelineTag: String
) {
    val displayName: String
        get() = repoId.substringAfter('/')

    val recommendedFile: HuggingFaceFile
        get() = files.first()
}

data class HuggingFaceFile(
    val filename: String,
    val quantization: String,
    val sizeBytes: Long
) {
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(sizeBytes.toDouble() / 1024.0 / 1024.0 / 1024.0)
            sizeBytes >= 1024L * 1024L -> "%.0f MB".format(sizeBytes.toDouble() / 1024.0 / 1024.0)
            sizeBytes >= 1024L -> "%.0f KB".format(sizeBytes.toDouble() / 1024.0)
            sizeBytes > 0 -> "$sizeBytes B"
            else -> "大小未知"
        }
}
