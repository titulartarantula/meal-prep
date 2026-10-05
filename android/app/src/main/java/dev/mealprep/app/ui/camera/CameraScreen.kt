package dev.mealprep.app.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.ui.common.MessageText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the camera part of the screen can do right now. */
enum class CameraMode { Ready, NoPermission, Unavailable }

/** The label of the main (shoot) button. */
fun shootLabel(s: PagesState, busy: Boolean): String = when {
    busy -> "Saving…"
    s.retaking != null -> "Retake page ${s.retaking + 1}"
    s.pages.isEmpty() -> "Snap page"
    else -> "Add page"
}

/**
 * Snap page → Add page / Done. Thumbnails can be moved earlier/later, retaken or deleted before Done. Without the
 * camera (permission refused, or CameraX failed) the phone's camera app or the photo picker still work.
 */
@Composable
fun CameraContent(
    title: String,
    pages: PagesState,
    busy: Boolean,
    error: String?,
    mode: CameraMode,
    preview: @Composable () -> Unit,
    onShoot: () -> Unit,
    onSystemCamera: () -> Unit,
    onPick: () -> Unit,
    onAllowCamera: () -> Unit,
    onRetake: (Int) -> Unit,
    onCancelRetake: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (mode) {
                CameraMode.Ready -> preview()
                CameraMode.NoPermission -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Meal Prep needs the camera to photograph cookbook pages. You can also choose photos you already took.")
                    Button(onClick = onAllowCamera) { Text("Allow camera") }
                }
                CameraMode.Unavailable -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The camera couldn't start here. Use the phone's camera app instead, or choose photos you already took.")
                    Button(onClick = onSystemCamera, enabled = pages.canShoot && !busy) { Text("Use the camera app") }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        MessageText(error)
        if (pages.pages.isNotEmpty()) {
            Text("${pages.pages.size} of ${PagesState.MAX_PAGES} pages, in reading order", Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall)
            PageStrip(pages, retakeEnabled = mode != CameraMode.NoPermission, onRetake = onRetake, onRemove = onRemove, onMove = onMove)
        }
        if (pages.retaking != null) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Retaking page ${pages.retaking + 1}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = onCancelRetake) { Text("Keep the old one") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            OutlinedButton(onClick = onPick, enabled = pages.canShoot && !busy && pages.retaking == null) { Text("From Photos") }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShoot, enabled = mode == CameraMode.Ready && pages.canShoot && !busy, modifier = Modifier.weight(1f)) {
                Text(shootLabel(pages, busy))
            }
            Button(onClick = onDone, enabled = pages.pages.isNotEmpty() && !busy, modifier = Modifier.weight(1f)) {
                Text("Done (${pages.pages.size})")
            }
        }
        if (!pages.canShoot && pages.retaking == null) {
            Text("That's the most a recipe can have (${PagesState.MAX_PAGES} pages).", Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Thumbnails in reading order, each with Earlier / Later / Retake / Delete. */
@Composable
fun PageStrip(
    pages: PagesState, retakeEnabled: Boolean, onRetake: ((Int) -> Unit)?, onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        itemsIndexed(pages.pages, key = { _, f -> f.path }) { i, f ->
            val n = i + 1
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Thumbnail(f, "Page $n", highlighted = pages.retaking == i)
                Text(if (pages.retaking == i) "Retaking…" else "Page $n", style = MaterialTheme.typography.labelMedium)
                Row {
                    TextButton(onClick = { onMove(i, -1) }, enabled = i > 0,
                        modifier = Modifier.semantics { contentDescription = "Move page $n earlier" }) { Text("◀") }
                    TextButton(onClick = { onMove(i, 1) }, enabled = i < pages.pages.lastIndex,
                        modifier = Modifier.semantics { contentDescription = "Move page $n later" }) { Text("▶") }
                }
                Row {
                    if (onRetake != null) TextButton(onClick = { onRetake(i) }, enabled = retakeEnabled,
                        modifier = Modifier.semantics { contentDescription = "Retake page $n" }) { Text("Retake") }
                    TextButton(onClick = { onRemove(i) },
                        modifier = Modifier.semantics { contentDescription = "Delete page $n" }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun Thumbnail(f: File, description: String, highlighted: Boolean = false) {
    val bmp by produceState<ImageBitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
        }
    }
    val m = Modifier.width(72.dp).height(96.dp).let {
        if (highlighted) it.border(3.dp, MaterialTheme.colorScheme.primary) else it
    }
    bmp?.let { Image(it, description, m) } ?: Box(m.semantics { contentDescription = description })
}

@Composable
fun CameraScreen(vm: CameraViewModel, title: String, onDone: (File) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted && !asked) { asked = true; ask.launch(Manifest.permission.CAMERA) } }
    // Granted in system Settings while away: pick it up on return.
    LifecycleResumeEffect(Unit) { granted = hasPermission(); onPauseOrDispose { } }

    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val controller = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
    }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        controller.bindToLifecycle(owner)
        controller.initializationFuture.addListener({
            val ok = runCatching { controller.initializationFuture.get() }.isSuccess &&
                runCatching { controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            if (!ok) unavailable = true
        }, ContextCompat.getMainExecutor(ctx))
    }
    DisposableEffect(Unit) { onDispose { controller.unbind() } }

    // Fallbacks: the phone's camera app (needs the CAMERA permission too, since the app declares it) and the photo picker.
    var systemRaw by remember { mutableStateOf<File?>(null) }
    val systemCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        systemRaw?.let { raw -> if (ok && raw.length() > 0) vm.onCaptured(raw) else raw.delete() }
        systemRaw = null
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PagesState.MAX_PAGES)) { uris: List<Uri> ->
        vm.onPicked(uris)
    }

    val mode = when { !granted -> CameraMode.NoPermission; unavailable -> CameraMode.Unavailable; else -> CameraMode.Ready }
    CameraContent(
        title, state, busy, error, mode,
        preview = {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                PreviewView(c).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    // The screen already keeps clear of the system bars; the preview must not pad itself again.
                    ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ -> WindowInsetsCompat.CONSUMED }
                }
            })
        },
        onShoot = {
            val raw = vm.rawFile()
            controller.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(), ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = vm.onCaptured(raw)
                    override fun onError(e: ImageCaptureException) = vm.onCaptureFailed(raw)
                })
        },
        onSystemCamera = {
            val raw = vm.rawFile()
            systemRaw = raw
            runCatching { systemCamera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.pages", raw)) }
                .onFailure { vm.onCaptureFailed(raw); systemRaw = null }
        },
        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onAllowCamera = {
            // Refused for good (Android stops showing the prompt): only the app's settings page can grant it.
            val canAsk = !asked || ctx.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
            if (canAsk) { asked = true; ask.launch(Manifest.permission.CAMERA) }
            else ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        },
        onRetake = vm::retake, onCancelRetake = vm::cancelRetake, onRemove = vm::remove, onMove = vm::move,
        onDone = { onDone(vm.done()) }, onCancel = onCancel,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
