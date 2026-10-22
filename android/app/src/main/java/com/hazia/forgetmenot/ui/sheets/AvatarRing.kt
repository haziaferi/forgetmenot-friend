package com.hazia.forgetmenot.ui.sheets

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hazia.forgetmenot.domain.initials
import com.hazia.forgetmenot.ui.theme.AppTheme

/**
 * avatarRingSVG() (www/js/ui/avatar.js), shared by home (50 dp) and detail (96 dp): the photo or
 * the initials inside a ring filled to classify()'s pct from 12 o'clock, so the ring filling up
 * and the friend moving up the list are one fact shown twice. Both web callers pass stroke 3,
 * which sets the geometry (ring radius size/2 − 3, avatar 4 inside it), while app.css draws the
 * stroke 2 wide and eases the fill (.ring-fill transition). The track is half border, half bg,
 * so the primary arc clears 3:1 against it in every palette (.ring-track). Decorative: callers
 * add their own semantics through [modifier].
 */
@Composable
fun AvatarRing(name: String, avatar: String?, pct: Float, size: Dp, modifier: Modifier = Modifier) {
    val track = lerp(AppTheme.colors.border, MaterialTheme.colorScheme.background, 0.5f)
    val fill = MaterialTheme.colorScheme.primary
    val shown by animateFloatAsState(pct.coerceIn(0f, 1f), label = "ring")
    val photo = remember(avatar) { decodeAvatar(avatar) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2 - RING_INSET.toPx()
            val topLeft = Offset(center.x - r, center.y - r)
            val arc = Size(r * 2, r * 2)
            drawCircle(track, r, style = Stroke(RING_STROKE.toPx()))
            if (shown > 0f) drawArc(fill, -90f, 360f * shown, false, topLeft, arc, style = Stroke(RING_STROKE.toPx()))
        }
        val inner = size - (RING_INSET + AVATAR_GAP) * 2
        if (photo != null) {
            Image(photo, null, Modifier.size(inner).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(inner).background(AppTheme.colors.decorative, CircleShape), contentAlignment = Alignment.Center) {
                Text(
                    initials(name),
                    color = AppTheme.colors.onDecorative,
                    style = MaterialTheme.typography.titleLarge, // the display face (.avatar-init)
                    fontWeight = FontWeight.Bold,
                    fontSize = (inner.value * 0.4f).sp, // avR × 0.8
                    maxLines = 1,
                )
            }
        }
    }
}

private val RING_INSET = 3.dp
private val RING_STROKE = 2.dp
private val AVATAR_GAP = 4.dp

/** A hydrated avatar (data:image/…;base64,…) as a bitmap, or null for none or unreadable. */
private fun decodeAvatar(avatar: String?): ImageBitmap? {
    if (avatar == null || !avatar.startsWith("data:image/")) return null
    return runCatching {
        val bytes = Base64.decode(avatar.substringAfter(','), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
