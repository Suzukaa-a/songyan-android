package app.songyan.ime.ime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import app.songyan.ime.appearance.BackgroundFill
import app.songyan.ime.appearance.BackgroundImage
import java.util.concurrent.Executors

/**
 * Decodes the skin's background picture off the main thread and keeps at most one bitmap.
 *
 * The keyboard draws its panel colour until [onReady] fires, so a 1 MiB file never delays the first
 * frame or a key press. The bitmap is sampled down to the view's size (the picture is only ever
 * drawn to cover the keyboard window) and dropped as soon as another appearance is shown. It is never
 * recycled explicitly: a frame already recorded may still reference it, so the garbage collector
 * frees it once nothing draws it.
 */
internal object BackgroundImageLoader {
    private class Loaded(
        val cacheKey: String,
        val bitmap: Bitmap,
        val sample: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "songyan-background-image").also { it.isDaemon = true }
        }

    // The three fields below are only touched on the main thread.
    private var current: Loaded? = null
    private var pending: String? = null

    /** Bumped by every new request and release: a finished decode only lands for the request that started it. */
    private var generation = 0

    /**
     * The bitmap for [fill] when it is already decoded for this size, otherwise null after starting a
     * decode that calls [onReady] on the main thread. A bitmap cached for another picture is released.
     */
    fun request(
        fill: BackgroundFill.Image,
        width: Int,
        height: Int,
        onReady: () -> Unit,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        if (current?.cacheKey != fill.cacheKey) release()
        val held = current
        if (held != null &&
            held.sample == BackgroundImage.coverSampleSize(held.sourceWidth, held.sourceHeight, width, height)
        ) {
            return held.bitmap
        }
        val tag = fill.cacheKey + ":" + width + "x" + height
        if (pending == tag) return held?.bitmap
        val mine = ++generation
        pending = tag
        worker.execute {
            val decoded = decode(fill, width, height)
            main.post {
                if (mine != generation) return@post
                pending = null
                if (decoded != null) {
                    current = decoded
                    onReady()
                }
            }
        }
        return held?.bitmap
    }

    /** Drops the bitmap and cancels a decode in flight; called when the appearance has no picture. */
    fun release() {
        generation++
        pending = null
        current = null
    }

    private fun decode(
        fill: BackgroundFill.Image,
        width: Int,
        height: Int,
    ): Loaded? {
        val bytes = BackgroundImage.bytesOf(fill.dataUrl) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = BackgroundImage.coverSampleSize(bounds.outWidth, bounds.outHeight, width, height)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return Loaded(fill.cacheKey, bitmap, sample, bounds.outWidth, bounds.outHeight)
    }
}
