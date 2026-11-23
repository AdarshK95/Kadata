package com.kannadadoc.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.DocumentParserFacade
import com.kannadadoc.parser.ParsedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    uri: Uri,
    fileName: String,
    parserFacade: DocumentParserFacade,
    knOnly: Boolean,
    onBack: () -> Unit,
    onOpenExtractedDocument: (ParsedDocument) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isExtracting by remember { mutableStateOf(false) }

    val renderMutex = remember { Mutex() }
    // Memory-budgeted LRU bitmap cache: RGB_565 halves RAM usage per page and LruCache caps
    // total native PDF page memory to 1/8 of max heap (16 MB - 64 MB) to prevent OOM on large books.
    val bitmapCache = remember(uri) {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L)
            .toInt()
            .coerceIn(16 * 1024, 64 * 1024)
        object : LruCache<Int, Bitmap>(maxKb) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }
    var cacheVersion by remember { mutableIntStateOf(0) }

    // Zoom & pan state (reset automatically on page turn)
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Open ParcelFileDescriptor & PdfRenderer safely on IO dispatcher
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Unable to open PDF file")
                val pdfRenderer = PdfRenderer(descriptor)
                pfd = descriptor
                renderer = pdfRenderer
                pageCount = pdfRenderer.pageCount
            }.onFailure { err ->
                loadError = err.localizedMessage ?: "Failed to render PDF"
            }
        }
    }

    DisposableEffect(uri) {
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            bitmapCache.evictAll()
        }
    }

    val safePageCount = pageCount.coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { safePageCount })

    // Reset zoom when navigating to another page
    LaunchedEffect(pagerState.currentPage) {
        zoomScale = 1f
        panOffset = Offset.Zero
    }

    Scaffold(
        topBar = {
            // Sleek, non-colliding 48dp header (MainActivity handles top status bar strip)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                    )

                    if (zoomScale > 1.05f) {
                        IconButton(
                            onClick = {
                                zoomScale = 1f
                                panOffset = Offset.Zero
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Default.FitScreen,
                                contentDescription = "Reset zoom",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (isExtracting) return@Button
                            scope.launch {
                                isExtracting = true
                                val result = parserFacade.parseDocument(context, uri, fileName, DocFormat.PDF)
                                isExtracting = false
                                result
                                    .onSuccess { extracted ->
                                        val hasText = extracted.blocks.any { blk ->
                                            when (blk) {
                                                is DocBlock.Paragraph -> blk.runs.any { it.text.isNotBlank() }
                                                is DocBlock.Table -> blk.rows.any { row ->
                                                    row.any { cell -> cell.any { p -> p.runs.any { it.text.isNotBlank() } } }
                                                }
                                            }
                                        }
                                        val finalExtracted = if (!hasText && extracted.warningMessage.isNullOrBlank()) {
                                            extracted.copy(
                                                warningMessage = "No selectable text was found in this PDF (it appears to be a scanned image). You can still type notes in this blank document • ಈ PDF ನಲ್ಲಿ ಆಯ್ಕೆಮಾಡಬಹುದಾದ ಪಠ್ಯ ಕಂಡುಬಂದಿಲ್ಲ (ಇದು ಸ್ಕ್ಯಾನ್ ಮಾಡಿದ ಚಿತ್ರವಾಗಿರಬಹುದು)."
                                            )
                                        } else {
                                            extracted
                                        }
                                        onOpenExtractedDocument(finalExtracted)
                                    }
                                    .onFailure { err ->
                                        snackbarHostState.showSnackbar(
                                            err.localizedMessage ?: "Could not extract text from this PDF"
                                        )
                                    }
                            }
                        },
                        enabled = !isExtracting && loadError == null,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        if (isExtracting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (knOnly) "ತಿದ್ದುಪಡಿ" else "Edit",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (pageCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Page Navigation (< Page X/Y >)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        val prev = (pagerState.currentPage - 1).coerceAtLeast(0)
                                        scope.launch { pagerState.animateScrollToPage(prev) }
                                    },
                                    enabled = pagerState.currentPage > 0,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "Previous page",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Text(
                                    text = if (knOnly) {
                                        "${pagerState.currentPage + 1} / $pageCount"
                                    } else {
                                        "Page ${pagerState.currentPage + 1}/$pageCount"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                FilledTonalIconButton(
                                    onClick = {
                                        val next = (pagerState.currentPage + 1).coerceAtMost(pageCount - 1)
                                        scope.launch { pagerState.animateScrollToPage(next) }
                                    },
                                    enabled = pagerState.currentPage < pageCount - 1,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Next page",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Always-Visible PDF Zoom Controls (- | 100% | +)
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            val next = (zoomScale - 0.25f).coerceIn(1f, 4f)
                                            zoomScale = next
                                            if (next <= 1.02f) panOffset = Offset.Zero
                                        },
                                        enabled = zoomScale > 1.01f,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ZoomOut,
                                            contentDescription = "Zoom out",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Text(
                                        text = "${(zoomScale * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (zoomScale > 1.05f) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                zoomScale = 1f
                                                panOffset = Offset.Zero
                                            }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )

                                    IconButton(
                                        onClick = {
                                            zoomScale = (zoomScale + 0.25f).coerceIn(1f, 4f)
                                        },
                                        enabled = zoomScale < 3.99f,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ZoomIn,
                                            contentDescription = "Zoom in",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            when {
                loadError != null -> {
                    Text(
                        text = loadError ?: "Unable to open PDF",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp)
                    )
                }

                pageCount == 0 -> {
                    CircularProgressIndicator()
                }

                else -> {
                    // Natural horizontal swipe/slide between pages when at 1x zoom;
                    // smooth pinch-to-zoom (1x..4x), pan when zoomed, and double-tap zoom.
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = zoomScale <= 1.05f,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        PdfZoomablePage(
                            pageIndex = pageIndex,
                            renderer = renderer,
                            renderMutex = renderMutex,
                            bitmapCache = bitmapCache,
                            cacheVersion = cacheVersion,
                            onBitmapCached = { cacheVersion++ },
                            zoomScale = if (pagerState.currentPage == pageIndex) zoomScale else 1f,
                            panOffset = if (pagerState.currentPage == pageIndex) panOffset else Offset.Zero,
                            onTransformChange = { newScale, newOffset ->
                                zoomScale = newScale
                                panOffset = newOffset
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfZoomablePage(
    pageIndex: Int,
    renderer: PdfRenderer?,
    renderMutex: Mutex,
    bitmapCache: LruCache<Int, Bitmap>,
    cacheVersion: Int,
    onBitmapCached: () -> Unit,
    zoomScale: Float,
    panOffset: Offset,
    onTransformChange: (Float, Offset) -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var renderError by remember(pageIndex, renderer) { mutableStateOf<String?>(null) }
    val cachedBitmap = remember(pageIndex, cacheVersion) { bitmapCache.get(pageIndex) }

    LaunchedEffect(pageIndex, renderer) {
        if (renderer != null && bitmapCache.get(pageIndex) == null) {
            renderError = null
            val renderResult = withContext(Dispatchers.IO) {
                renderMutex.withLock {
                    runCatching {
                        renderer.openPage(pageIndex).use { page ->
                            // PdfRenderer.Page.render() strictly requires ARGB_8888 on Android;
                            // cap longest edge at 1600px (~7 MB/page) inside our heap-bounded LruCache.
                            val targetScale = minOf(2.0f, 1600f / maxOf(page.width, page.height).coerceAtLeast(1))
                            val w = (page.width * targetScale).roundToInt().coerceAtLeast(1)
                            val h = (page.height * targetScale).roundToInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(AndroidColor.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    }
                }
            }
            renderResult
                .onSuccess { bmp ->
                    bitmapCache.put(pageIndex, bmp)
                    onBitmapCached()
                }
                .onFailure { err ->
                    renderError = err.message ?: "Unable to render page ${pageIndex + 1}"
                }
        }
    }

    // Keep latest transform state via rememberUpdatedState so pointerInput(pageIndex) NEVER cancels mid-pinch!
    val latestScale by rememberUpdatedState(zoomScale)
    val latestOffset by rememberUpdatedState(panOffset)
    val latestSize by rememberUpdatedState(containerSize)
    val latestOnTransform by rememberUpdatedState(onTransformChange)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onDoubleTap = { tapPos ->
                        if (latestScale > 1.2f) {
                            latestOnTransform(1f, Offset.Zero)
                        } else {
                            val targetScale = 2.25f
                            val sz = latestSize
                            val maxX = ((sz.width * (targetScale - 1f)) / 2f).coerceAtLeast(0f)
                            val maxY = ((sz.height * (targetScale - 1f)) / 2f).coerceAtLeast(0f)
                            val centerX = sz.width / 2f
                            val centerY = sz.height / 2f
                            val offsetX = ((centerX - tapPos.x) * (targetScale - 1f)).coerceIn(-maxX, maxX)
                            val offsetY = ((centerY - tapPos.y) * (targetScale - 1f)).coerceIn(-maxY, maxY)
                            latestOnTransform(targetScale, Offset(offsetX, offsetY))
                        }
                    }
                )
            }
            .pointerInput(pageIndex) {
                // Stable multi-touch gesture handler (never restarts mid-pinch):
                // - 2+ fingers ALWAYS perform focal-point pinch-to-zoom & pan cleanly
                // - 1 finger pans smoothly when zoomed in (scale > 1.05f)
                // - 1 finger at 1x zoom is NOT consumed so HorizontalPager swipes pages effortlessly!
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var curScale = latestScale
                    var curOffset = latestOffset
                    var gestureActive = false
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }

                        if (pressedCount >= 2 || curScale > 1.05f || gestureActive) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val sz = latestSize

                            val nextScale = (curScale * zoomChange).coerceIn(1f, 4f)
                            val maxX = ((sz.width * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                            val maxY = ((sz.height * (nextScale - 1f)) / 2f).coerceAtLeast(0f)

                            val nextOffset = if (nextScale <= 1.02f) {
                                Offset.Zero
                            } else {
                                // Anchor pinch zoom around the user's fingers (focal-point zoom)
                                val scaleRatio = if (curScale > 0f) nextScale / curScale else 1f
                                val center = Offset(sz.width / 2f, sz.height / 2f)
                                val focalDelta = if (centroid != Offset.Unspecified && pressedCount >= 2) {
                                    (centroid - center - curOffset) * (1f - scaleRatio)
                                } else {
                                    Offset.Zero
                                }
                                Offset(
                                    x = (curOffset.x + panChange.x + focalDelta.x).coerceIn(-maxX, maxX),
                                    y = (curOffset.y + panChange.y + focalDelta.y).coerceIn(-maxY, maxY)
                                )
                            }

                            if (pressedCount >= 2) gestureActive = true

                            if (nextScale != curScale || nextOffset != curOffset) {
                                curScale = nextScale
                                curOffset = nextOffset
                                latestOnTransform(curScale, curOffset)
                            }

                            if (pressedCount >= 2 || curScale > 1.05f) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (cachedBitmap != null) {
            Image(
                bitmap = cachedBitmap.asImageBitmap(),
                contentDescription = "PDF Page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale,
                        translationX = panOffset.x,
                        translationY = panOffset.y
                    )
                    .shadow(4.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White)
            )
        } else if (renderError != null) {
            Text(
                text = renderError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            CircularProgressIndicator()
        }
    }
}
