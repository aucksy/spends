package com.spends.app.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class DriveException(message: String) : Exception(message)

/** A backup file inside the app's "Spends Backup" Drive folder. */
data class DriveFile(val id: String, val name: String, val modifiedTime: String?, val size: Long?)

/**
 * Minimal Google Drive REST v3 client (PRD §4.12). Uses only the OAuth access token + OkHttp — no
 * heavy google-api-client. With the **drive.file** scope the app can only see files/folders it
 * created, so it keeps backups in a visible "Spends Backup" folder in My Drive without ever seeing
 * the rest of the user's Drive.
 */
@Singleton
class DriveClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gzipType = "application/gzip".toMediaType()
    private val jsonType = "application/json; charset=UTF-8".toMediaType()
    private val folderMime = "application/vnd.google-apps.folder"

    /** Find the "Spends Backup" folder, creating it if missing. Returns its file id. */
    suspend fun ensureBackupFolder(token: String): String = withContext(Dispatchers.IO) {
        val q = "mimeType='$folderMime' and name='$FOLDER_NAME' and trashed=false"
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            // Oldest first, so if a duplicate folder ever exists every caller deterministically
            // resolves to the same (original) one.
            .addQueryParameter("orderBy", "createdTime")
            .addQueryParameter("fields", "files(id,name)")
            .build()
        val lookup = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        val existing = client.newCall(lookup).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DriveException("Drive folder lookup failed (${resp.code})")
            val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
            if (files.length() > 0) files.getJSONObject(0).getString("id") else null
        }
        existing ?: run {
            val metadata = JSONObject().put("name", FOLDER_NAME).put("mimeType", folderMime).toString()
            val create = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?fields=id")
                .header("Authorization", "Bearer $token")
                .post(metadata.toRequestBody(jsonType))
                .build()
            client.newCall(create).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw DriveException("Drive folder create failed (${resp.code})")
                JSONObject(body).getString("id")
            }
        }
    }

    /** List backup files inside [folderId], newest first. */
    suspend fun list(token: String, folderId: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$folderId' in parents and trashed=false")
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("fields", "files(id,name,modifiedTime,size)")
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DriveException("Drive list failed (${resp.code})")
            val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
            (0 until files.length()).map { i ->
                val o = files.getJSONObject(i)
                DriveFile(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    modifiedTime = o.optString("modifiedTime").ifEmpty { null },
                    size = o.optString("size").toLongOrNull(),
                )
            }
        }
    }

    /** Create a new file inside [folderId] (multipart: metadata + bytes). Returns the new file id. */
    suspend fun create(token: String, name: String, bytes: ByteArray, folderId: String): String = withContext(Dispatchers.IO) {
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
            .toString()
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody(jsonType))
            .addPart(bytes.toRequestBody(gzipType))
            .build()
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DriveException("Drive upload failed (${resp.code})")
            JSONObject(respBody).getString("id")
        }
    }

    suspend fun download(token: String, fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw DriveException("Drive download failed (${resp.code})")
            resp.body?.bytes() ?: throw DriveException("Empty backup file")
        }
    }

    suspend fun delete(token: String, fileId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw DriveException("Drive delete failed (${resp.code})")
        }
    }

    companion object {
        const val FOLDER_NAME = "Spends Backup"
    }
}
