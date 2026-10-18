package com.kar.reader.ui.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.data.db.PageTextEntity
import com.kar.reader.data.files.DocumentImporter
import com.kar.reader.data.files.DocumentStore
import com.kar.reader.data.files.ImportResult
import com.kar.reader.data.files.MetadataOverride
import com.kar.reader.data.repo.ReadingRepository
import com.kar.reader.feature.scan.ScanProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val store: DocumentStore,
    private val importer: DocumentImporter,
    private val reading: ReadingRepository,
) : ViewModel() {
    /** Deskewed pages ready for the PDF. Kept in memory only until saved. */
    val pages = mutableStateListOf<Bitmap>()
    val busy = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    fun addPage(photo: Bitmap, corners: List<PointF>) = viewModelScope.launch {
        busy.value = "Straightening page…"
        val page = withContext(Dispatchers.Default) { ScanProcessor.deskew(photo, corners) }
        photo.recycle()
        pages.add(page)
        busy.value = null
    }

    fun removeLast() { pages.removeLastOrNull()?.recycle() }

    fun save(title: String, onSaved: (String) -> Unit) = viewModelScope.launch {
        if (pages.isEmpty()) return@launch
        val dir = store.newStagingDir()
        val file = File(dir, "scan.pdf")
        try {
            val texts = ScanProcessor.buildPdf(pages.toList(), file) { done, total -> busy.value = "Recognizing text on page $done of $total…" }
            busy.value = "Adding to your library…"
            val staged = importer.stageLocal(file, "$title.pdf", "SCAN", MetadataOverride(title = title)).getOrThrow()
            val result = importer.commit(listOf(staged)).firstOrNull()
            val bookId = when (result) {
                is ImportResult.Imported -> result.book.id
                is ImportResult.Duplicate -> result.existing.id
                else -> throw IllegalStateException((result as? ImportResult.Failed)?.reason ?: "Couldn't save the scan")
            }
            reading.savePageText(texts.mapIndexed { i, t -> PageTextEntity(bookId, i, t) })
            pages.forEach { it.recycle() }
            pages.clear()
            onSaved(bookId)
        } catch (e: Exception) {
            error.value = e.message ?: "Couldn't save the scan"
        } finally {
            busy.value = null
            dir.deleteRecursively()
        }
    }

    override fun onCleared() {
        pages.forEach { it.recycle() }
        super.onCleared()
    }
}

private fun ImageProxy.toUprightBitmap(): Bitmap {
    val bmp = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bmp
    val m = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it != bmp) bmp.recycle() }
}

@Composable
fun ScanScreen(onBack: () -> Unit, onSaved: (String) -> Unit, vm: ScanViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    var captured by remember { mutableStateOf<Bitmap?>(null) }
    var naming by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }

    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().background(Color(0xFF12100D))) {
        KarTopBar("Scan to PDF", subtitle = "On-device text recognition · works offline", onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val shot = captured
            when {
                !hasCamera -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("KAR READER needs the camera only while you scan. Photos stay on this device.", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    GlossyButton("Allow camera", onClick = { permission.launch(Manifest.permission.CAMERA) }, kind = GlossyKind.GOLD)
                }
                shot != null -> CornerAdjuster(shot, onConfirm = { corners -> vm.addPage(shot, corners); captured = null }, onRetake = { shot.recycle(); captured = null })
                else -> CameraPreview(capture, Modifier.fillMaxSize())
            }
        }
        busy?.let { Column(Modifier.padding(16.dp)) { Text(it, color = Color.White); Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } }
        error?.let { Text(it, color = Color(0xFFFFB4A8), modifier = Modifier.padding(horizontal = 16.dp)) }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.pages.forEach { p -> Image(p.asImageBitmap(), null, Modifier.height(56.dp).aspectRatio(p.width.toFloat() / p.height)) }
                if (vm.pages.isNotEmpty()) Text("${vm.pages.size} page${if (vm.pages.size == 1) "" else "s"}", color = Color.White, modifier = Modifier.align(Alignment.CenterVertically))
            }
            if (captured == null && hasCamera) {
                Box(
                    Modifier.size(76.dp).background(Color.White, CircleShape).padding(6.dp).background(Color(0xFFE6E6E6), CircleShape)
                        .clickable { takePicture(context, capture) { captured = it } },
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Icon(Icons.Filled.CameraAlt, "Capture page", tint = Color(0xFF333333)) }
                Spacer(Modifier.width(12.dp))
            }
            if (vm.pages.isNotEmpty() && captured == null) {
                Column(horizontalAlignment = Alignment.End) {
                    GlossyButton("Done", onClick = { naming = true }, kind = GlossyKind.GOLD, icon = Icons.Filled.Check, enabled = busy == null)
                    TextButton(onClick = vm::removeLast) { Text("Remove last", color = Color.White) }
                }
            }
        }
    }
    if (naming) {
        var title by remember { mutableStateOf("Scan ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())}") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save scan") },
            text = { Column { Text("Text is recognized on this device (Latin-script languages). Handwriting isn't recognized."); OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Title") }) } },
            confirmButton = { TextButton(onClick = { naming = false; vm.save(title.ifBlank { "Scan" }, onSaved) }) { Text("Save PDF") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

private fun takePicture(context: Context, capture: ImageCapture, onResult: (Bitmap) -> Unit) {
    capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val bmp = runCatching { image.toUprightBitmap() }.getOrNull()
            image.close()
            bmp?.let(onResult)
        }
        override fun onError(exception: ImageCaptureException) = Unit
    })
}

@Composable
private fun CameraPreview(capture: ImageCapture, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Shows the photo with four draggable corners; the page is straightened to them. */
@Composable
private fun CornerAdjuster(photo: Bitmap, onConfirm: (List<PointF>) -> Unit, onRetake: () -> Unit) {
    val corners = remember { mutableStateListOf<PointF>().apply { addAll(ScanProcessor.defaultCorners()) } }
    var active by remember { mutableStateOf(-1) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Drag the gold corners to the page edges", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp))
        Box(Modifier.weight(1f).padding(16.dp).aspectRatio(photo.width.toFloat() / photo.height, matchHeightConstraintsFirst = true)) {
            Image(photo.asImageBitmap(), null, Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            active = corners.indices.minByOrNull { i -> val c = corners[i]; (Offset(c.x * size.width, c.y * size.height) - pos).getDistance() } ?: -1
                        },
                        onDragEnd = { active = -1 },
                        onDrag = { change, amount ->
                            change.consume()
                            if (active >= 0) {
                                val c = corners[active]
                                corners[active] = PointF((c.x + amount.x / size.width).coerceIn(0f, 1f), (c.y + amount.y / size.height).coerceIn(0f, 1f))
                            }
                        },
                    )
                },
            ) {
                val pts = corners.map { Offset(it.x * size.width, it.y * size.height) }
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, Color(0x22F4C268))
                drawPath(path, Color(0xFFF4C268), style = Stroke(3.dp.toPx()))
                pts.forEach { drawCircle(Color(0xFFF4C268), 12.dp.toPx(), it); drawCircle(Color.White, 12.dp.toPx(), it, style = Stroke(2.dp.toPx())) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
            GlossyButton("Retake", onClick = onRetake, kind = GlossyKind.PEARL)
            GlossyButton("Use page", onClick = { onConfirm(corners.toList()) }, kind = GlossyKind.GOLD, icon = Icons.Filled.Check)
        }
        Text("Tip: lay the page flat in good light.", color = Color(0xFFCFC7B8), fontSize = 12.sp)
    }
}

