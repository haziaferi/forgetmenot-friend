package com.hazia.forgetmenot.integrations

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.hazia.forgetmenot.domain.isValidAvatar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AvatarsTest {
    /** Red | green | blue thirds, side by side: a centre crop keeps only the green. */
    private fun stripes(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { i, col ->
            p.color = col
            c.drawRect(i * w / 3f, 0f, (i + 1) * w / 3f, h.toFloat(), p)
        }
        return b
    }

    private fun decode(uri: String): Bitmap {
        val bytes = Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun near(expected: Int, actual: Int) =
        abs(Color.red(expected) - Color.red(actual)) < 40 &&
            abs(Color.green(expected) - Color.green(actual)) < 40 &&
            abs(Color.blue(expected) - Color.blue(actual)) < 40

    @Test fun centreCropTo128Jpeg() {
        val uri = avatarDataUri(stripes(900, 300))
        assertTrue(uri.startsWith("data:image/jpeg;base64,"))
        assertTrue("passes the import check", isValidAvatar(uri))
        val out = decode(uri)
        assertEquals(128, out.width)
        assertEquals(128, out.height)
        for ((x, y) in listOf(4 to 4, 64 to 64, 123 to 123)) {
            assertTrue("($x,$y) ${Integer.toHexString(out.getPixel(x, y))}", near(Color.GREEN, out.getPixel(x, y)))
        }
    }

    @Test fun portraitCropsTopAndBottom() {
        val tall = Bitmap.createBitmap(200, 600, Bitmap.Config.ARGB_8888)
        Canvas(tall).apply {
            drawColor(Color.RED)
            drawRect(0f, 200f, 200f, 400f, Paint().apply { color = Color.BLUE })
        }
        val out = decode(avatarDataUri(tall))
        assertTrue(near(Color.BLUE, out.getPixel(2, 2)))
        assertTrue(near(Color.BLUE, out.getPixel(125, 125)))
    }

    @Test fun sampleSizeKeepsTheShortSideAt128() {
        assertEquals(1, sampleSizeFor(100, 100))
        assertEquals(1, sampleSizeFor(255, 4000))
        assertEquals(2, sampleSizeFor(256, 4000))
        assertEquals(32, sampleSizeFor(8000, 6000)) // 6000/32 = 187; /64 = 93 is too small
        assertEquals(32, sampleSizeFor(8192, 4096)) // exactly 128 at /32
    }

    @Test fun decoderReadsBytesAndSubsamples() = runBlocking {
        val png = ByteArrayOutputStream().also { stripes(1200, 600).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val images = DecoderAvatarImages(RuntimeEnvironment.getApplication().contentResolver)
        val out = decode(images.fromBytes(png))
        assertEquals(128, out.width)
        assertTrue(near(Color.GREEN, out.getPixel(64, 64)))
    }
}
