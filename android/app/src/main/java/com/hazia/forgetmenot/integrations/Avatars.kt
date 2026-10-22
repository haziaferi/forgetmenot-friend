package com.hazia.forgetmenot.integrations

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** resizeImageFile()'s output size and canvas.toDataURL('image/jpeg', 0.85) quality. */
const val AVATAR_SIZE = 128
private const val AVATAR_JPEG_QUALITY = 85

/** Turns a picked photo or a contact's photo into an avatar data URI; throws when it cannot be read. */
interface AvatarImages {
    suspend fun fromUri(uri: Uri): String
    suspend fun fromBytes(bytes: ByteArray): String
}

/** The largest power of two that still leaves the short side at least [target] pixels. */
fun sampleSizeFor(width: Int, height: Int, target: Int = AVATAR_SIZE): Int {
    var s = 1
    while (minOf(width, height) / (s * 2) >= target) s *= 2
    return s
}

/** resizeImageFile(): the centred square, scaled to [size]×[size], as a JPEG data URI. */
fun avatarDataUri(src: Bitmap, size: Int = AVATAR_SIZE): String {
    val s = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
    val scaled = Bitmap.createScaledBitmap(square, size, size, true)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/**
 * ImageDecoder applies the EXIF rotation (the Camera plugin's correctOrientation) and decodes a
 * large photo subsampled, so a 50 MP picture never sits in memory at full size.
 */
class DecoderAvatarImages(private val resolver: ContentResolver) : AvatarImages {
    override suspend fun fromUri(uri: Uri): String =
        withContext(Dispatchers.IO) { decode(ImageDecoder.createSource(resolver, uri)) }

    override suspend fun fromBytes(bytes: ByteArray): String =
        withContext(Dispatchers.Default) { decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }

    private fun decode(source: ImageDecoder.Source): String =
        avatarDataUri(ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height))
        })
}
