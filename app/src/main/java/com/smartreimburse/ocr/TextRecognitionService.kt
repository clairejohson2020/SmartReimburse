package com.smartreimburse.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import kotlinx.coroutines.tasks.await

class TextRecognitionService(private val context: Context) {
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    suspend fun recognizeText(imagePath: String): String {
        val image = InputImage.fromFilePath(context, Uri.fromFile(File(imagePath)))
        return recognizer.process(image).await().text
    }
}
