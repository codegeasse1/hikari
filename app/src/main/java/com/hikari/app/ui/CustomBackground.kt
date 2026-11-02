package com.hikari.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user's own picture as the app background (Settings -> Customize ->
 * "Custom background").
 *
 * The picture is copied into the app's private storage (so it keeps working
 * after the gallery picture is moved or deleted) and drawn behind every screen
 * by [CustomBackgroundLayer], which AppRoot places right after the theme's own
 * backdrop. A "darkness" overlay in the theme's background colour keeps text
 * readable over any picture. Without a picture nothing changes: the normal
 * theme backdrop is what shows.
 */
object CustomBackground {
    private const val PREFS = "hikari_custom_bg"
    private const val KEY_DIM = "dim"
    private const val DEFAULT_DIM = 0.45f
    private const val MAX_DIM = 0.9f

    /** The longest side the saved picture is scaled down to (keeps memory sane). */
    private const val MAX_SIDE = 2048

    /** The picture being shown, or null for the normal theme background. */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /** How strongly the theme colour is laid over the picture, 0 (none) .. 0.9. */
    var dim by mutableFloatStateOf(DEFAULT_DIM)
        private set

    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "custom_bg/background.jpg")

    /** Reads the saved picture and darkness once per process. */
    suspend fun load(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        val result = withContext(Dispatchers.IO) {
            val f = file(app)
            val bmp = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
            val savedDim = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_DIM, DEFAULT_DIM)
            Pair(bmp?.asImageBitmap(), savedDim)
        }
        image = result.first
        dim = result.second.coerceIn(0f, MAX_DIM)
    }

    /** Saves the picture at [uri] as the background. False when it can't be read. */
    suspend fun set(context: Context, uri: Uri): Boolean {
        val app = context.applicationContext
        val bmp = withContext(Dispatchers.IO) {
            val decoded = decodeScaled(app, uri) ?: return@withContext null
            val f = file(app)
            f.parentFile?.mkdirs()
            val ok = runCatching {
                f.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }.getOrDefault(false)
            if (ok) decoded else null
        } ?: return false
        loaded = true
        image = bmp.asImageBitmap()
        return true
    }

    /** Goes back to the normal theme background. */
    suspend fun clear(context: Context) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) { runCatching { file(app).delete() } }
        loaded = true
        image = null
    }

    /** Live change while the slider moves; [saveDim] stores it when it stops. */
    fun setDim(value: Float) {
        dim = value.coerceIn(0f, MAX_DIM)
    }

    fun saveDim(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_DIM, dim).apply()
    }

    /** Decodes [uri], fixes its rotation and scales it so the longest side is at most [MAX_SIDE]. */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= MAX_SIDE) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            val orientation = runCatching {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val upright = if (degrees != 0f) {
                Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(degrees) }, true,
                )
            } else {
                raw
            }

            val side = maxOf(upright.width, upright.height)
            return if (side > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / side
                Bitmap.createScaledBitmap(
                    upright,
                    (upright.width * scale).toInt().coerceAtLeast(1),
                    (upright.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                upright
            }
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }
}

/**
 * The theme's real page colour, provided by HikariTheme. While a custom
 * background picture is set the scheme's own `background` is made see-through,
 * so the darkness layer over the picture takes its colour from here instead.
 */
val LocalPageScrim = staticCompositionLocalOf { Color.Black }

/**
 * A page fill that steps aside for the custom background picture: the normal
 * theme colour (at [alpha]) without a picture, nothing with one.
 */
@Composable
fun pageFill(alpha: Float): Color =
    if (CustomBackground.image != null) Color.Transparent
    else MaterialTheme.colorScheme.background.copy(alpha = alpha)

/**
 * Drawn by AppRoot behind the whole app, over the theme's own backdrop. Draws
 * nothing while no picture is set.
 */
@Composable
fun CustomBackgroundLayer() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CustomBackground.load(context) }
    val picture = CustomBackground.image ?: return
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalPageScrim.current.copy(alpha = CustomBackground.dim))
        )
    }
}

/** The body of the "Custom background" card in Settings -> Customize. */
@Composable
fun CustomBackgroundContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { CustomBackground.load(context) }

    var busy by remember { mutableStateOf(false) }
    val picture = CustomBackground.image

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val ok = CustomBackground.set(context, uri)
            busy = false
            Toast.makeText(
                context,
                I18n.t(if (ok) "Background updated" else "Could not read that image"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Text(
        tr("Pick a picture from your gallery and use it as the background of the whole app."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    // Preview: the picture with the same darkness the app will show.
    val previewShape = RoundedCornerShape(16.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(previewShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), previewShape),
    ) {
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = tr("Background preview"),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LocalPageScrim.current.copy(alpha = CustomBackground.dim))
            )
        } else {
            Text(
                tr("Default background"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { picker.launch("image/*") }, enabled = !busy) {
            Text(tr(if (picture == null) "Choose from gallery" else "Change picture"))
        }
        if (picture != null) {
            TextButton(
                onClick = {
                    scope.launch {
                        CustomBackground.clear(context)
                        Toast.makeText(context, I18n.t("Background removed"), Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !busy,
            ) {
                Text(tr("Remove"))
            }
        }
    }

    if (picture != null) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Darkness"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                (CustomBackground.dim * 100).roundToInt().toString() + "%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = CustomBackground.dim,
            onValueChange = { CustomBackground.setDim(it) },
            onValueChangeFinished = { CustomBackground.saveDim(context) },
            valueRange = 0f..0.9f,
        )
        Text(
            tr("A darker overlay keeps the text easy to read over a bright picture."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
