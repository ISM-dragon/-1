package com.example.ondeviceai

import android.content.Context
import java.io.File

internal class AssetModelStore(private val context: Context) {
    fun materialize(assetPath: String): File {
        val output = File(context.filesDir, "ondevice-ai/$assetPath")
        if (output.isFile && output.length() > 0L) return output
        output.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            output.outputStream().use { outputStream -> input.copyTo(outputStream) }
        }
        check(output.isFile && output.length() > 0L) { "Unable to materialize asset: $assetPath" }
        return output
    }
}
