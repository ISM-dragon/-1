package com.example.core.repository

import android.content.Context
import android.net.Uri
import com.example.core.network.ApiClient
import com.example.data.model.GatewayConfig
import com.example.data.video.MediaUriStabilizer
import java.io.File

/** Owns local media lifetime; it does not decide remote job state. */
class MediaRepository(
    context: Context,
    private val apiClient: ApiClient = ApiClient(context.applicationContext.contentResolver)
) {
    private val appContext = context.applicationContext

    fun stabilize(source: Uri, displayName: String): Uri =
        if (source.scheme == "content" || source.scheme == "file") {
            MediaUriStabilizer.copyForBackground(appContext, source, displayName)
        } else source

    suspend fun upload(
        config: GatewayConfig,
        source: Uri,
        onProgress: (suspend (Int) -> Unit)? = null
    ): Result<ApiClient.UploadResource> = apiClient.upload(config, source, onProgress)

    suspend fun download(
        config: GatewayConfig,
        mediaUrl: String,
        destination: File
    ): Result<File> = apiClient.download(config, mediaUrl, destination)

    suspend fun downloadAll(
        config: GatewayConfig,
        outputs: List<ApiClient.RemoteOutput>,
        destinationDirectory: File
    ): Result<Map<String, File>> = runCatching {
        require(outputs.isNotEmpty()) { "لا توجد نتائج قابلة للتنزيل" }
        require(destinationDirectory.exists() || destinationDirectory.mkdirs()) { "تعذر إنشاء مجلد النتائج" }
        outputs.mapIndexed { index, output ->
            val destination = File(destinationDirectory, "clip_${index + 1}.mp4")
            output.mediaUrl to download(config, output.mediaUrl, destination).getOrThrow()
        }.toMap()
    }

    fun deleteManagedSource(uriString: String): Boolean =
        MediaUriStabilizer.deleteManagedCopy(appContext, uriString)
}
