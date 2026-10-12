package pl.jack.floatchat.overlay

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/** Zapis zrzutu ekranu jako JPEG w cache (udostępniany przez FileProvider). */
object Screenshots {

    class Shot(val uri: Uri, val bytes: ByteArray)

    /** Dłuższy bok zmniejszany do tylu pikseli — mniejszy plik, czat i tak skaluje obraz. */
    private const val MAX_SIDE = 2048

    fun save(context: Context, bitmap: Bitmap): Shot {
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()

        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // trzymamy tylko ostatni zrzut
        val file = File(dir, "zrzut-${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Shot(uri, bytes)
    }
}
