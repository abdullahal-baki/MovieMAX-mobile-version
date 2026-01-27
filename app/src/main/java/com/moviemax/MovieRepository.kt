package com.moviemax

import android.app.Application
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val APP_VERSION = "1.0-mobile"

val SERVER_LIST = listOf(
    "45.250.20.254",
    "172.16.50.7",
    "10.16.100.213",
    "10.100.100.12",
    "10.16.100.202",
    "10.16.100.212",
    "10.16.100.206",
    "103.153.175.254/NAS1",
    "server1.dhakamovie.com/",
    "data.kenecolor.com/data/",
)

const val DB_URL =
    "https://github.com/alamin-sarkar/test/raw/refs/heads/main/test/movie_database.zip"
const val DB_VERSION_URL =
    "https://raw.githubusercontent.com/alamin-sarkar/test/refs/heads/main/test/db_version.txt"
const val APP_VERSION_URL =
    "https://raw.githubusercontent.com/abdullahal-baki/movie-max/refs/heads/main/version-mobile.txt"
const val APP_RELEASES_URL =
    "https://github.com/abdullahal-baki/movie-max/releases"

data class MovieResult(
    val title: String,
    val link: String,
    val score: Int,
    val posterLink: String?,
    val baseName: String,
    val year: Int?
)

@Serializable
data class HistoryEntry(
    val link: String,
    val name: String,
    val position: Long,
    val duration: Long,
    val lastPlayedTs: Long,
    val baseName: String? = null,
    val posterLink: String? = null,
    val localPosterPath: String? = null,
    val audioLabel: String? = null,
    val audioLanguage: String? = null,
    val audioGroupIndex: Int? = null,
    val audioTrackIndex: Int? = null,
    val subtitleLabel: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleGroupIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    val subtitleEnabled: Boolean = false,
    val subtitleUri: String? = null
)

@Serializable
data class AiCacheItem(
    val title: String,
    val link: String,
    val posterLink: String? = null,
    val baseName: String
)

@Serializable
data class AiCache(
    val timestamp: Long,
    val items: List<AiCacheItem>
)

class MovieRepository(private val app: Application) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val dbFile = File(app.filesDir, "movie_database.db")
    private val historyFile = File(app.filesDir, "history.json")
    private val versionFile = File(app.filesDir, "db_version.txt")
    private val posterCacheFile = File(app.filesDir, "posters_cache.json")
    private val aiCacheFile = File(app.filesDir, "ai_recommendations.json")
    private val posterDir = File(app.filesDir, "poster_cache")

    private val headClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val versionClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val omdbClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val posterClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun dbReady(): Boolean = dbFile.exists()

    private fun posterFileForKey(key: String): File {
        if (!posterDir.exists()) posterDir.mkdirs()
        val safe = key.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return File(posterDir, "$safe.jpg")
    }

    fun getLocalPosterPath(key: String): String? {
        if (key.isBlank()) return null
        val file = posterFileForKey(key)
        return if (file.exists()) file.absolutePath else null
    }

    suspend fun downloadPosterToFile(url: String, key: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank() || key.isBlank()) return@withContext null
        if (url.startsWith("file://")) return@withContext url
        val file = posterFileForKey(key)
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            val request = Request.Builder().url(url).get().build()
            posterClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                temp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            if (temp.exists() && temp.length() > 0) {
                if (file.exists()) file.delete()
                temp.renameTo(file)
                return@withContext file.absolutePath
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun getLocalDbVersion(): String? {
        if (!versionFile.exists()) return null
        return try {
            versionFile.readText(Charsets.UTF_8).trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun saveLocalDbVersion(version: String) {
        try {
            versionFile.writeText(version.trim(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    suspend fun fetchRemoteDbVersion(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            versionClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response body")
                Result.success(body.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadDb(
        url: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        val tempFile = File(dbFile.parentFile, "${dbFile.name}.tmp")
        val tempZip = File(dbFile.parentFile, "${dbFile.name}.tmp.zip")
        repeat(3) { attempt ->
            try {
                if (tempFile.exists()) tempFile.delete()
                if (tempZip.exists()) tempZip.delete()
                val request = Request.Builder().url(url).get().build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response body")
                    val total = body.contentLength()
                    dbFile.parentFile?.mkdirs()
                    val downloadTarget = if (url.lowercase().endsWith(".zip")) tempZip else tempFile
                    FileOutputStream(downloadTarget).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var read: Int
                            var bytesRead = 0L
                            while (true) {
                                read = input.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                bytesRead += read
                                onProgress?.invoke(bytesRead, total)
                            }
                        }
                    }
                }
                if (url.lowercase().endsWith(".zip")) {
                    unzipDb(tempZip, tempFile)
                }
                if (!isValidSqlite(tempFile)) {
                    tempFile.delete()
                    throw IOException("Downloaded DB is not valid")
                }
                if (dbFile.exists()) dbFile.delete()
                if (!tempFile.renameTo(dbFile)) {
                    tempFile.copyTo(dbFile, overwrite = true)
                    tempFile.delete()
                }
                if (tempZip.exists()) tempZip.delete()
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                lastError = e
                tempFile.delete()
                tempZip.delete()
                dbFile.delete()
                if (attempt < 2) {
                    delay(1500L * (attempt + 1))
                }
            }
        }
        Result.failure(lastError ?: IOException("Download failed"))
    }

    private fun unzipDb(zipFile: File, outFile: File) {
        if (!zipFile.exists()) throw IOException("Zip file missing")
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var extracted = false
            while (entry != null) {
                val name = entry.name.lowercase()
                if (!entry.isDirectory && (name.endsWith(".db") || name.contains("movie_database"))) {
                    outFile.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                    extracted = true
                    break
                }
                entry = zis.nextEntry
            }
            if (!extracted) {
                throw IOException("DB file not found in zip")
            }
        }
    }

    private fun isValidSqlite(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        return try {
            val header = ByteArray(16)
            file.inputStream().use { input ->
                val read = input.read(header)
                if (read < 16) return false
            }
            val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            header.contentEquals(magic)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun pingServer(server: String): Boolean = withContext(Dispatchers.IO) {
        val url = if (server.startsWith("http")) server else "http://$server"
        val request = Request.Builder().url(url).head().build()
        try {
            headClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    fun matchMovies(
        availableServers: List<String>,
        searchName: String,
        year: String?
    ): List<MovieResult> {
        if (searchName.isBlank()) return emptyList()
        if (!dbFile.exists()) return emptyList()

        val normalizedQuery = searchName.trim().lowercase()
        val searchQueries = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (searchQueries.isEmpty()) return emptyList()

        val matched = mutableListOf<MovieResult>()
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cursor = if (year != null) {
                db.rawQuery(
                    "SELECT name, full_name, year, link, poster_link FROM Movies WHERE year = ?",
                    arrayOf(year)
                )
            } else {
                db.rawQuery("SELECT name, full_name, year, link, poster_link FROM Movies", null)
            }
            cursor.use {
                val nameIndex = cursor.getColumnIndex("name")
                val fullNameIndex = cursor.getColumnIndex("full_name")
                val linkIndex = cursor.getColumnIndex("link")
                val yearIndex = cursor.getColumnIndex("year")
                val posterIndex = cursor.getColumnIndex("poster_link")

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val fullName = cursor.getString(fullNameIndex)
                    val link = cursor.getString(linkIndex) ?: continue
                    val poster = if (posterIndex >= 0) cursor.getString(posterIndex) else null
                    val yearValue = if (yearIndex >= 0) {
                        cursor.getString(yearIndex)?.toIntOrNull()
                    } else {
                        null
                    }
                    if (link.isBlank()) continue

                    val movieName = name.lowercase().trim()
                    val tags = movieName.split(Regex("\\s+")).filter { it.isNotBlank() }
                    var score = 0
                    for (query in searchQueries) {
                        for (tag in tags) {
                            if (query == tag) score += 1
                        }
                    }
                    if (movieName.contains(normalizedQuery)) score += 1000

                    if (score > 0 && isValidLink(availableServers, link)) {
                        var title = (fullName ?: name).trim()
                        if (title.length > 55) title = title.take(55) + "..."
                        matched.add(
                            MovieResult(
                                title = title,
                                link = link,
                                score = score,
                                posterLink = poster,
                                baseName = name,
                                year = yearValue
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
        return matched.sortedByDescending { it.score }.take(20)
    }

    fun loadHistory(): List<HistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val text = historyFile.readText(Charsets.UTF_8)
            json.decodeFromString(ListSerializer(HistoryEntry.serializer()), text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHistory(entries: List<HistoryEntry>) {
        try {
            val text = json.encodeToString(ListSerializer(HistoryEntry.serializer()), entries)
            historyFile.writeText(text, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun loadPosterCache(): Map<String, String> {
        if (!posterCacheFile.exists()) return emptyMap()
        return try {
            val text = posterCacheFile.readText(Charsets.UTF_8)
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                text
            )
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun savePosterCache(cache: Map<String, String>) {
        try {
            val filtered = cache.filterValues { it.isNotBlank() }
            if (filtered.isEmpty()) {
                if (posterCacheFile.exists()) posterCacheFile.delete()
                return
            }
            val text = json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                filtered
            )
            posterCacheFile.writeText(text, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun loadAiCache(): AiCache? {
        if (!aiCacheFile.exists()) return null
        return try {
            val text = aiCacheFile.readText(Charsets.UTF_8)
            json.decodeFromString(AiCache.serializer(), text)
        } catch (_: Exception) {
            null
        }
    }

    fun saveAiCache(cache: AiCache?) {
        try {
            if (cache == null || cache.items.isEmpty()) {
                if (aiCacheFile.exists()) aiCacheFile.delete()
                return
            }
            val text = json.encodeToString(AiCache.serializer(), cache)
            aiCacheFile.writeText(text, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun openExternal(link: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(link), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            app.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidLink(availableServers: List<String>, link: String): Boolean {
        for (server in availableServers) {
            if (link.contains(server)) return true
        }
        return false
    }

    suspend fun fetchOmdbPoster(title: String, apiKey: String): String? = withContext(Dispatchers.IO) {
        if (title.isBlank() || apiKey.isBlank()) return@withContext null
        try {
            val query = URLEncoder.encode(title, "UTF-8")
            val url = "https://www.omdbapi.com/?apikey=$apiKey&s=$query&type=movie"
            val request = Request.Builder().url(url).get().build()
            omdbClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val root = (json.parseToJsonElement(body) as? JsonObject) ?: return@withContext null
                val ok = (root["Response"] as? JsonPrimitive)?.content ?: "False"
                if (ok != "True") return@withContext null
                val search = root["Search"] as? JsonArray ?: return@withContext null
                for (item in search) {
                    val obj = item as? JsonObject ?: continue
                    val poster = (obj["Poster"] as? JsonPrimitive)?.content
                    if (!poster.isNullOrBlank() && poster != "N/A") {
                        return@withContext poster
                    }
                }
            }
            val exactUrl = "https://www.omdbapi.com/?apikey=$apiKey&t=$query&type=movie"
            val exactRequest = Request.Builder().url(exactUrl).get().build()
            omdbClient.newCall(exactRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val root = (json.parseToJsonElement(body) as? JsonObject) ?: return@withContext null
                val ok = (root["Response"] as? JsonPrimitive)?.content ?: "False"
                if (ok != "True") return@withContext null
                val poster = (root["Poster"] as? JsonPrimitive)?.content
                if (poster.isNullOrBlank() || poster == "N/A") null else poster
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun findPosterByBaseName(baseName: String): String? = withContext(Dispatchers.IO) {
        val name = baseName.trim()
        if (name.isBlank()) return@withContext null
        if (!dbFile.exists()) return@withContext null
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery(
                "SELECT poster_link FROM Movies WHERE name = ? COLLATE NOCASE AND poster_link IS NOT NULL AND poster_link != '' LIMIT 1",
                arrayOf(name)
            ).use { cursor ->
                return@withContext if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            db.close()
        }
    }
}
