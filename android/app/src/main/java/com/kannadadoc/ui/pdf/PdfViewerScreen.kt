package com.kannadadoc.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.DocumentParserFacade
import com.kannadadoc.parser.ParsedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tablet-first native PDF viewer using Android's PdfRenderer.
 * - Supports 1-page & 2-page landscape spread mode (ideal for Lenovo Idea Tab Pro 12.7").
 * - Pinch-to-zoom and button zoom.
 * - One-tap "Edit Text" invokes PDFBox-Android to extract & convert legacy Kannada fonts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    uri: Uri,
    fileName: String,
    parserFacade: DocumentParserFacade,
    bilingualLabels: Boolean,
    onBack: () -> Unit,
    onOpenExtractedDocument: (ParsedDocument) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pdfPfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var twoPageMode by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var renderedBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isExtractingText by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Open PdfRenderer
    DisposableEffect(uri) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val renderer = PdfRenderer(pfd)
                pdfPfd = pfd
                pdfRenderer = renderer
                pageCount = renderer.pageCount
            } else {
                errorMessage = "Could not open PDF file descriptor."
            }
        } catch (e: Exception) {
            errorMessage = e.localizedMessage ?: "Failed to render PDF"
        }

        onDispose {
            renderedBitmaps.forEach { it.recycle() }
            renderedBitmaps = emptyList()
            runCatching { pdfRenderer?.close() }
            runCatching { pdfPfd?.close() }
            pdfRenderer = null
            pdfPfd = null
        }
    }

    // Render active page(s) on IO thread
    LaunchedEffect(pdfRenderer, currentPage, twoPageMode) {
        val renderer = pdfRenderer ?: return@LaunchedEffect
        if (pageCount == 0) return@LaunchedEffect

        val indices = if (twoPageMode && currentPage + 1 < pageCount) {
            listOf(currentPage, currentPage + 1)
        } else {
            listOf(currentPage)
        }

        val bitmaps = withContext(Dispatchers.IO) {
            indices.map { idx ->
                renderer.openPage(idx).use { page ->
                    // Render at 2x DPI for sharp text on 12.7" 3K tablet display
                    val width = (page.width * 2).coerceAtMost(2400)
                    val height = (page.height * 2).coerceAtMost(3200)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }

        renderedBitmaps.forEach { it.recycle() }
        renderedBitmaps = bitmaps
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (pageCount > 0) {
                            Text(
                                text = "Page ${currentPage + 1} of $pageCount",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(1f) }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(3f) }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                    }
                    IconButton(onClick = { twoPageMode = !twoPageMode }) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = "Toggle 2-page spread",
                            tint = if (twoPageMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            isExtractingText = true
                            scope.launch {
                                parserFacade.parseDocument(context, uri, fileName, DocFormat.PDF)
                                    .onSuccess { extracted ->
                                        isExtractingText = false
                                        onOpenExtractedDocument(extracted)
                                    }
                                    .onFailure { err ->
                                        isExtractingText = false
                                        errorMessage = err.localizedMessage ?: "PDF text extraction failed"
                                    }
                            }
                        },
                        enabled = !isExtractingText
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (bilingualLabels) "Edit Text • ತಿದ್ದುಪಡಿ" else "Edit Text")
                    }
                    Spacer(Modifier.width(8.dp))
                }
            )
        },
        bottomBar = {
            if (pageCount > 1) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val step = if (twoPageMode) 2 else 1
                        FilledTonalIconButton(
                            onClick = { currentPage = (currentPage - step).coerceAtLeast(0) },
                            enabled = currentPage > 0,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                        }

                        Text(
                            text = if (twoPageMode && currentPage + 1 < pageCount) {
                                "Pages ${currentPage + 1}–${currentPage + 2} / $pageCount"
                            } else {
                                "Page ${currentPage + 1} / $pageCount"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )

                        FilledTonalIconButton(
                            onClick = {
                                val next = currentPage + step
                                if (next < pageCount) currentPage = next
                            },
                            enabled = currentPage + step < pageCount,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(1f, 3f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                errorMessage != null -> {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp)
                    )
                }

                isExtractingText -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Extracting Kannada text from PDF...", style = MaterialTheme.typography.titleMedium)
                    }
                }

                renderedBitmaps.isEmpty() -> {
                    CircularProgressIndicator()
                }

                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                            .graphicsLayer(scaleX = zoomScale, scaleY = zoomScale),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.Top
                    ) {
                        renderedBitmaps.forEach { bmp ->
                            Card(
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "PDF page",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
