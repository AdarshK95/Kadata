package com.kannadadoc.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    bilingualLabels: Boolean,
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
    val bitmapCache = remember { mutableStateMapOf<Int, Bitmap>() }

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
            bitmapCache.clear()
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
                                        onOpenExtractedDocument(extracted)
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
                            text = if (bilingualLabels) "Edit" else "ತಿದ್ದುಪಡಿ",
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
                                .height(54.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    val prev = (pagerState.currentPage - 1).coerceAtLeast(0)
                                    scope.launch { pagerState.animateScrollToPage(prev) }
                                },
                                enabled = pagerState.currentPage > 0,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "Previous page"
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (bilingualLabels) {
                                        "Page ${pagerState.currentPage + 1} / $pageCount"
                                    } else {
                                        "ಪುಟ ${pagerState.currentPage + 1} / $pageCount"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (zoomScale > 1.05f) {
                                    Text(
                                        text = "${(zoomScale * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            FilledTonalIconButton(
                                onClick = {
                                    val next = (pagerState.currentPage + 1).coerceAtMost(pageCount - 1)
                                    scope.launch { pagerState.animateScrollToPage(next) }
                                },
                                enabled = pagerState.currentPage < pageCount - 1,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Next page"
                                )
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
    bitmapCache: MutableMap<Int, Bitmap>,
    zoomScale: Float,
    panOffset: Offset,
    onTransformChange: (Float, Offset) -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val cachedBitmap = bitmapCache[pageIndex]

    LaunchedEffect(pageIndex, renderer) {
        if (renderer != null && bitmapCache[pageIndex] == null) {
            val bmp = withContext(Dispatchers.IO) {
                renderMutex.withLock {
                    runCatching {
                        renderer.openPage(pageIndex).use { page ->
                            // Render crisp high-resolution bitmap (~2.2x PDF points, capped safely at 2200px)
                            val targetScale = minOf(2.2f, 2200f / maxOf(page.width, page.height).coerceAtLeast(1))
                            val w = (page.width * targetScale).roundToInt().coerceAtLeast(1)
                            val h = (page.height * targetScale).roundToInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(AndroidColor.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    }.getOrNull()
                }
            }
            if (bmp != null) {
                // Keep memory bounded: keep only current +/- 3 pages cached
                val keysToDrop = bitmapCache.keys.filter { kotlin.math.abs(it - pageIndex) > 3 }
                keysToDrop.forEach { bitmapCache.remove(it) }
                bitmapCache[pageIndex] = bmp
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(zoomScale) {
                detectTapGestures(
                    onDoubleTap = {
                        if (zoomScale > 1.2f) {
                            onTransformChange(1f, Offset.Zero)
                        } else {
                            onTransformChange(2.2f, Offset.Zero)
                        }
                    }
                )
            }
            .pointerInput(zoomScale, panOffset, containerSize) {
                // Custom multi-touch handler:
                // - 2+ fingers ALWAYS pinch-to-zoom & pan cleanly
                // - 1 finger pans ONLY when zoomed in (zoomScale > 1.05f)
                // - 1 finger at 1x zoom is NOT consumed so HorizontalPager swipes effortlessly!
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var curScale = zoomScale
                    var curOffset = panOffset
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (pressedCount >= 2 || curScale > 1.05f) {
                            val nextScale = (curScale * zoomChange).coerceIn(1f, 4f)
                            val maxX = ((containerSize.width * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                            val maxY = ((containerSize.height * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                            val nextOffset = if (nextScale <= 1.02f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    x = (curOffset.x + panChange.x).coerceIn(-maxX, maxX),
                                    y = (curOffset.y + panChange.y).coerceIn(-maxY, maxY)
                                )
                            }
                            if (nextScale != curScale || nextOffset != curOffset) {
                                curScale = nextScale
                                curOffset = nextOffset
                                onTransformChange(curScale, curOffset)
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
        } else {
            CircularProgressIndicator()
        }
    }
}
