package com.smartreimburse.camera

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.smartreimburse.data.AttachmentType
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AttachmentStore(private val context: Context) {
    private val timestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun createImageFile(type: AttachmentType): File {
        val directory = attachmentDirectory(type)
        val timestamp = timestampFormatter.format(Date())
        return File(directory, "${type.name.lowercase(Locale.US)}_$timestamp.jpg")
    }

    fun copyImageUriToPrivateStorage(uri: Uri, type: AttachmentType): String {
        val extension = context.contentResolver.getType(uri)
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "jpg"
        val directory = attachmentDirectory(type)
        val timestamp = timestampFormatter.format(Date())
        val outputFile = File(directory, "${type.name.lowercase(Locale.US)}_$timestamp.$extension")

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取选择的图片" }
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        return outputFile.absolutePath
    }

    fun deleteFile(path: String) {
        runCatching { File(path).delete() }
    }

    private fun attachmentDirectory(type: AttachmentType): File {
        return File(context.filesDir, "attachments/${type.name.lowercase(Locale.US)}")
            .apply { mkdirs() }
    }
}
