package io.github.playertwo1.bookconverter.data

import java.io.File

data class MobiExtractResult(
    val status: String,
    val format: String,
    val title: String?,
    val author: String?,
    val language: String?,
    val errorMessage: String?
)

object NativeMobiBridge {
    init {
        System.loadLibrary("mobi_jni")
    }

    external fun extractMobiToDir(inputPath: String, outputDir: String): Array<String?>

    fun extract(inputFile: File, outputDir: File): MobiExtractResult {
        val result = extractMobiToDir(inputFile.absolutePath, outputDir.absolutePath)
        return MobiExtractResult(
            status = result.getOrNull(0) ?: "ERROR",
            format = result.getOrNull(1) ?: "",
            title = result.getOrNull(2),
            author = result.getOrNull(3),
            language = result.getOrNull(4),
            errorMessage = result.getOrNull(5)
        )
    }
}
