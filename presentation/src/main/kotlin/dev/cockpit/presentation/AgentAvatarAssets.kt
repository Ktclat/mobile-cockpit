package dev.cockpit.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID

internal object AgentAvatarAssets {
    private const val MAX_BYTES = 30 * 1024 * 1024
    private const val MAX_EDGE = 4_096

    fun save(context: Context, bytes: ByteArray): Result<String> = runCatching {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
            "Choose an image smaller than 30 MiB."
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..MAX_EDGE && bounds.outHeight in 1..MAX_EDGE) {
            "Choose a valid image no larger than 4096 × 4096."
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("The selected image could not be decoded.")
        try {
            val directory = File(context.filesDir, "agent-avatars")
            check(directory.isDirectory || directory.mkdirs()) {
                "The avatar directory could not be created."
            }
            val target = File(directory, UUID.randomUUID().toString() + ".png")
            try {
                target.outputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            } catch (error: Exception) {
                target.delete()
                throw error
            }
            target.absolutePath
        } finally {
            bitmap.recycle()
        }
    }
}
