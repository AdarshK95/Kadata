package com.kannadadoc.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

enum class KeyboardAnchor {
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_LEFT,
    TOP_RIGHT
}

private enum class KeyboardTab {
    VARNAMALA,
    CLUSTERS,
    NUMBERS
}

private data class VariantPopupState(
    val rootKey: String,
    val selectedStem: String,
    val relatedStems: List<String>
)

private object KannadaVarnamalaData {
    val swaras: List<String> = listOf(
        "ಅ", "ಆ", "ಇ", "ಈ", "ಉ", "ಊ", "ಋ", "ೠ",
        "ಎ", "ಏ", "ಐ", "ಒ", "ಓ", "ಔ", "ಅಂ", "ಅಃ"
    )

    val vargaRows: List<List<String>> = listOf(
        listOf("ಕ", "ಖ", "ಗ", "ಘ", "ಙ"),
        listOf("ಚ", "ಛ", "ಜ", "ಝ", "ಞ"),
        listOf("ಟ", "ಠ", "ಡ", "ಢ", "ಣ"),
        listOf("ತ", "ಥ", "ದ", "ಧ", "ನ"),
        listOf("ಪ", "ಫ", "ಬ", "ಭ", "ಮ"),
        listOf("ಯ", "ರ", "ಲ", "ವ", "ಶ", "ಷ", "ಸ", "ಹ", "ಳ"),
        listOf("ಕ್ಷ", "ಜ್ಞ", "ಱ", "ೞ", "ಌ", "ೡ", "ೱ", "ೲ", "್")
    )

    val subscriptModifiers: List<Pair<String, String>> = listOf(
        "್ರ" to "್ರ", "್ಯ" to "್ಯ", "್ವ" to "್ವ", "್ನ" to "್ನ", "್ಮ" to "್ಮ",
        "್ತ" to "್ತ", "್ದ" to "್ದ", "್ಕ" to "್ಕ", "್ಗ" to "್ಗ", "್ಚ" to "್ಚ",
        "್ಜ" to "್ಜ", "್ಟ" to "್ಟ", "್ಡ" to "್ಡ", "್ಣ" to "್ಣ", "್ಪ" to "್ಪ",
        "್ಬ" to "್ಬ", "್ಲ" to "್ಲ", "್ಶ" to "್ಶ", "್ಷ" to "್ಷ", "್ಸ" to "್ಸ",
        "್ಹ" to "್ಹ", "್ಳ" to "್ಳ", "್ಖ" to "್ಖ", "್ಘ" to "್ಘ", "್ಙ" to "್ಙ",
        "್ಛ" to "್ಛ", "್ಝ" to "್ಝ", "್ಞ" to "್ಞ", "್ಠ" to "್ಠ", "್ಢ" to "್ಢ",
        "್ಥ" to "್ಥ", "್ಧ" to "್ಧ", "್ಫ" to "್ಫ", "್ಭ" to "್ಭ", "ರ್" to "ರ್"
    )

    val mixedClusters: List<String> = listOf(
        "ಕ್ಷ", "ಜ್ಞ", "ತ್ರ", "ಶ್ರ", "ಪ್ರ", "ಕ್ರ", "ಗ್ರ", "ದ್ರ", "ಬ್ರ", "ಭ್ರ", "ವ್ರ", "ಸ್ರ", "ಹ್ರ",
        "ಷ್ಟ್ರ", "ಸ್ತ್ರ", "ನ್ದ್ರ", "ತ್ರ್ಯ", "ಕ್ತ", "ಕ್ಲ", "ಕ್ವ", "ಗ್ನ", "ಗ್ಧ", "ಚ್ಯ", "ಜ್ವ", "ಜ್ಯ",
        "ಣ್ಯ", "ತ್ಯ", "ತ್ವ", "ತ್ಸ", "ದ್ಯ", "ದ್ವ", "ದ್ಧ", "ಧ್ವ", "ಧ್ಯ", "ನ್ಯ", "ನ್ವ", "ಪ್ತ", "ಪ್ಯ",
        "ಬ್ದ", "ಭ್ಯ", "ಮ್ಯ", "ಮ್ರ", "ಲ್ಯ", "ವ್ಯ", "ಶ್ಚ", "ಶ್ಯ", "ಶ್ವ", "ಷ್ಟ", "ಷ್ಠ", "ಷ್ಯ",
        "ಸ್ತ", "ಸ್ಥ", "ಸ್ನ", "ಸ್ಪ", "ಸ್ಮ", "ಸ್ಯ", "ಸ್ವ", "ಹ್ಮ", "ಹ್ಯ", "ಹ್ವ"
    )

    val doubledClusters: List<String> = listOf(
        "ಕ್ಕ", "ಗ್ಗ", "ಚ್ಚ", "ಜ್ಜ", "ಟ್ಟ", "ಡ್ಡ", "ಣ್ಣ", "ತ್ತ", "ದ್ದ", "ನ್ನ",
        "ಪ್ಪ", "ಬ್ಬ", "ಮ್ಮ", "ಯ್ಯ", "ರ್ರ", "ಲ್ಲ", "ವ್ವ", "ಶ್ಶ", "ಸ್ಸ", "ಳ್ಳ"
    )

    val arkavattuAndNasalClusters: List<String> = listOf(
        "ರ್ಕ", "ರ್ಗ", "ರ್ಚ", "ರ್ಜ", "ರ್ಣ", "ರ್ತ", "ರ್ಥ", "ರ್ದ", "ರ್ಧ", "ರ್ನ",
        "ರ್ಪ", "ರ್ಬ", "ರ್ಭ", "ರ್ಮ", "ರ್ಯ", "ರ್ಲ", "ರ್ವ", "ರ್ಶ", "ರ್ಷ", "ರ್ಸ", "ರ್ಹ",
        "ಂಕ", "ಂಗ", "ಂಚ", "ಂಜ", "ಂಟ", "ಂಡ", "ಂತ", "ಂದ", "ಂಪ", "ಂಬ", "ಂಸ"
    )

    val kannadaNumerals: List<String> = listOf("೦", "೧", "೨", "೩", "೪", "೫", "೬", "೭", "೮", "೯")
    val arabicNumerals: List<String> = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
    val punctuationSymbols: List<String> = listOf(
        ",", ".", "?", "!", ":", ";", "-", "—", "(", ")", "\"", "'", "/", "।", "॥", "ಽ", "₹", "%", "&", "ೱ", "ೲ"
    )

    private val vowelVariantsMap: Map<String, List<String>> = mapOf(
        "ಅ" to listOf("ಅ", "ಽ"),
        "ಆ" to listOf("ಆ", "ಾ"),
        "ಇ" to listOf("ಇ", "ಿ", "ೀ"),
        "ಈ" to listOf("ಈ", "ೀ", "ಿ"),
        "ಉ" to listOf("ಉ", "ು", "ೂ"),
        "ಊ" to listOf("ಊ", "ೂ", "ು"),
        "ಋ" to listOf("ಋ", "ೃ", "ೄ", "ೠ"),
        "ೠ" to listOf("ೠ", "ೄ", "ೃ", "ಋ"),
        "ಌ" to listOf("ಌ", "ೢ", "ೡ", "ೣ"),
        "ೡ" to listOf("ೡ", "ೣ", "ಌ", "ೢ"),
        "ಎ" to listOf("ಎ", "ೆ", "ೇ"),
        "ಏ" to listOf("ಏ", "ೇ", "ೆ"),
        "ಐ" to listOf("ಐ", "ೈ"),
        "ಒ" to listOf("ಒ", "ೊ", "ೋ"),
        "ಓ" to listOf("ಓ", "ೋ", "ೊ"),
        "ಔ" to listOf("ಔ", "ೌ"),
        "ಅಂ" to listOf("ಅಂ", "ಂ", "ಁ", "ೱ", "ೲ"),
        "ಅಃ" to listOf("ಅಃ", "ಃ", "ೱ", "ೲ", "ಽ"),
        "ೱ" to listOf("ೱ", "ೲ", "ಃ", "ಂ"),
        "ೲ" to listOf("ೲ", "ೱ", "ಃ", "ಂ"),
        "್" to listOf("್", "ಂ", "ಃ", "ಁ", "ಽ", "಼", "ೢ", "ೣ")
    )

    private val specialConjunctStems: Map<String, List<String>> = mapOf(
        "ಕ" to listOf("ಕ", "ಕ್ಕ", "ಕ್ಷ", "ಕ್ರ", "ಕ್ಯ"),
        "ಗ" to listOf("ಗ", "ಗ್ಗ", "ಗ್ರ", "ಗ್ಯ", "ಗ್ನ"),
        "ಚ" to listOf("ಚ", "ಚ್ಚ", "ಚ್ಯ"),
        "ಜ" to listOf("ಜ", "ಜ್ಜ", "ಜ್ಞ", "ಜ್ರ", "ಜ್ವ"),
        "ಟ" to listOf("ಟ", "ಟ್ಟ", "ಟ್ರ", "ಟ್ಯ"),
        "ಡ" to listOf("ಡ", "ಡ್ಡ", "ಡ್ರ", "ಡ್ಯ"),
        "ಣ" to listOf("ಣ", "ಣ್ಣ", "ಣ್ಯ"),
        "ತ" to listOf("ತ", "ತ್ತ", "ತ್ರ", "ತ್ಯ", "ತ್ವ"),
        "ದ" to listOf("ದ", "ದ್ದ", "ದ್ರ", "ದ್ಯ", "ದ್ವ"),
        "ಧ" to listOf("ಧ", "ಧ್ರ", "ಧ್ಯ", "ಧ್ವ"),
        "ನ" to listOf("ನ", "ನ್ನ", "ನ್ಯ", "ನ್ವ"),
        "ಪ" to listOf("ಪ", "ಪ್ಪ", "ಪ್ರ", "ಪ್ಯ", "ಪ್ತ"),
        "ಬ" to listOf("ಬ", "ಬ್ಬ", "ಬ್ರ", "ಬ್ಯ"),
        "ಭ" to listOf("ಭ", "ಭ್ರ", "ಭ್ಯ"),
        "ಮ" to listOf("ಮ", "ಮ್ಮ", "ಮ್ರ", "ಮ್ಯ"),
        "ಯ" to listOf("ಯ", "ಯ್ಯ"),
        "ರ" to listOf("ರ", "ರ್ರ", "ರ್ಯ"),
        "ಲ" to listOf("ಲ", "ಲ್ಲ", "ಲ್ಯ"),
        "ವ" to listOf("ವ", "ವ್ವ", "ವ್ರ", "ವ್ಯ"),
        "ಶ" to listOf("ಶ", "ಶ್ಶ", "ಶ್ರ", "ಶ್ವ", "ಶ್ಯ"),
        "ಷ" to listOf("ಷ", "ಷ್ಟ", "ಷ್ಠ", "ಷ್ಟ್ರ", "ಷ್ಯ"),
        "ಸ" to listOf("ಸ", "ಸ್ಸ", "ಸ್ತ", "ಸ್ಥ", "ಸ್ವ", "ಸ್ತ್ರ"),
        "ಹ" to listOf("ಹ", "ಹ್ರ", "ಹ್ಮ", "ಹ್ಯ", "ಹ್ವ"),
        "ಳ" to listOf("ಳ", "ಳ್ಳ", "ಳ್ಯ")
    )

    fun relatedStemsForKey(key: String): List<String> {
        if (vowelVariantsMap.containsKey(key)) return listOf(key)
        specialConjunctStems[key]?.let { return it }
        return if (key.length == 1) listOf(key, "${key}್${key}") else listOf(key)
    }

    fun variantsForStem(stem: String): List<String> {
        vowelVariantsMap[stem]?.let { return it }
        return listOf(
            stem,
            "${stem}್",
            "${stem}ಾ",
            "${stem}ಿ",
            "${stem}ೀ",
            "${stem}ು",
            "${stem}ೂ",
            "${stem}ೃ",
            "${stem}ೄ",
            "${stem}ೆ",
            "${stem}ೇ",
            "${stem}ೈ",
            "${stem}ೊ",
            "${stem}ೋ",
            "${stem}ೌ",
            "${stem}ಂ",
            "${stem}ಃ"
        )
    }
}

/**
 * Draggable 4-corner hover button rendered strictly inside the editor canvas Box
 * ONLY while the keyboard is closed. Default anchor: Bottom-Left; snaps to BL/BR/TL/TR.
 */
@Composable
fun FloatingKeyboardToggleButton(
    anchor: KeyboardAnchor,
    onAnchorChange: (KeyboardAnchor) -> Unit,
    onOpenKeyboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val boxWidthPx = with(density) { maxWidth.toPx() }
        val boxHeightPx = with(density) { maxHeight.toPx() }
        val buttonSizeDp = 44.dp
        val buttonSizePx = with(density) { buttonSizeDp.toPx() }
        val marginPx = with(density) { 12.dp.toPx() }

        val anchorX = when (anchor) {
            KeyboardAnchor.BOTTOM_LEFT, KeyboardAnchor.TOP_LEFT -> marginPx
            KeyboardAnchor.BOTTOM_RIGHT, KeyboardAnchor.TOP_RIGHT ->
                (boxWidthPx - buttonSizePx - marginPx).coerceAtLeast(marginPx)
        }
        val anchorY = when (anchor) {
            KeyboardAnchor.TOP_LEFT, KeyboardAnchor.TOP_RIGHT -> marginPx
            KeyboardAnchor.BOTTOM_LEFT, KeyboardAnchor.BOTTOM_RIGHT ->
                (boxHeightPx - buttonSizePx - marginPx).coerceAtLeast(marginPx)
        }

        val currentX = if (isDragging) dragX else anchorX
        val currentY = if (isDragging) dragY else anchorY

        Surface(
            onClick = onOpenKeyboard,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
            ),
            modifier = Modifier
                .offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
                .size(buttonSizeDp)
                .zIndex(20f)
                .pointerInput(boxWidthPx, boxHeightPx, anchor) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragX = anchorX
                            dragY = anchorY
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragX = (dragX + dragAmount.x).coerceIn(
                                marginPx,
                                (boxWidthPx - buttonSizePx - marginPx).coerceAtLeast(marginPx)
                            )
                            dragY = (dragY + dragAmount.y).coerceIn(
                                marginPx,
                                (boxHeightPx - buttonSizePx - marginPx).coerceAtLeast(marginPx)
                            )
                        },
                        onDragEnd = {
                            val centerX = dragX + buttonSizePx / 2f
                            val centerY = dragY + buttonSizePx / 2f
                            val leftHalf = centerX < boxWidthPx / 2f
                            val topHalf = centerY < boxHeightPx / 2f
                            onAnchorChange(
                                when {
                                    topHalf && leftHalf -> KeyboardAnchor.TOP_LEFT
                                    topHalf && !leftHalf -> KeyboardAnchor.TOP_RIGHT
                                    !topHalf && leftHalf -> KeyboardAnchor.BOTTOM_LEFT
                                    else -> KeyboardAnchor.BOTTOM_RIGHT
                                }
                            )
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = "Open Kannada Keyboard",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Adaptive Kannada Varnamala & Conjunct Clusters Keyboard Pane:
 * - Phone / Compact mode (isTabletSideMode = false):
 *     Docked flush at the bottom half, strictly capped <= 50% screen height so the top half
 *     is always the document. Includes bottom-right corner resizer grip.
 * - Tablet Side-by-Side mode (isTabletSideMode = true):
 *     Sits on the left side while the document moves to the right and fills all remaining width
 *     and full height. Does NOT enforce the 50% bottom-half cap (freely resizable up to 88% height
 *     and 52% width) and reports its top-edge limit so the cursor line always stays above it.
 */
@Composable
fun KannadaKeyboardPane(
    totalScreenWidth: Dp,
    totalScreenHeight: Dp,
    isTabletSideMode: Boolean,
    knOnly: Boolean,
    onTopLimitChanged: (topLimitCssPx: Int) -> Unit,
    onInsertText: (text: String, isSubscriptModifier: Boolean) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    val maxAllowedHeight = if (isTabletSideMode) {
        (totalScreenHeight * 0.88f).coerceAtLeast(260.dp)
    } else {
        (totalScreenHeight * 0.50f).coerceAtLeast(210.dp)
    }
    val minAllowedHeight = 210.dp.coerceAtMost(maxAllowedHeight)
    val defaultHeight = if (isTabletSideMode) {
        minOf(maxAllowedHeight, 440.dp)
    } else {
        minOf(maxAllowedHeight, 330.dp)
    }

    val maxAllowedWidth = if (isTabletSideMode) {
        (totalScreenWidth * 0.52f).coerceAtLeast(320.dp)
    } else {
        totalScreenWidth
    }
    val minAllowedWidth = 280.dp.coerceAtMost(maxAllowedWidth)
    val defaultWidth = if (isTabletSideMode) {
        minOf(maxAllowedWidth, 410.dp)
    } else {
        minOf(maxAllowedWidth, 580.dp)
    }

    var customHeightDp by remember(totalScreenHeight, isTabletSideMode) {
        mutableFloatStateOf(defaultHeight.value)
    }
    var customWidthDp by remember(totalScreenWidth, isTabletSideMode) {
        mutableFloatStateOf(defaultWidth.value)
    }
    var isResizing by remember { mutableStateOf(false) }

    val panelHeight = customHeightDp.dp.coerceIn(minAllowedHeight, maxAllowedHeight)
    val panelWidth = customWidthDp.dp.coerceIn(minAllowedWidth, maxAllowedWidth)

    // Report keyboard's top Y limit (in CSS px ~ dp) to Editor JS so cursor always stays above it
    LaunchedEffect(panelHeight, totalScreenHeight, isTabletSideMode) {
        val topLimitDp = (totalScreenHeight - panelHeight).value.roundToInt().coerceAtLeast(0)
        onTopLimitChanged(topLimitDp)
    }

    var activeTab by remember { mutableStateOf(KeyboardTab.VARNAMALA) }
    var variantPopup by remember { mutableStateOf<VariantPopupState?>(null) }

    BackHandler(enabled = true) {
        when {
            variantPopup != null -> variantPopup = null
            activeTab != KeyboardTab.VARNAMALA -> activeTab = KeyboardTab.VARNAMALA
            else -> onClose()
        }
    }

    val containerModifier = if (isTabletSideMode) {
        modifier
            .width(panelWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(end = 4.dp, top = 6.dp, bottom = 4.dp, start = 4.dp)
    } else {
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    }

    Box(
        modifier = containerModifier,
        contentAlignment = if (isTabletSideMode) Alignment.BottomStart else Alignment.BottomCenter
    ) {
        Surface(
            shape = if (isTabletSideMode) {
                RoundedCornerShape(14.dp)
            } else {
                RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
            },
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            modifier = Modifier
                .width(panelWidth)
                .height(panelHeight)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header: Back arrow (when in Clusters/Numbers) + Tab Switcher + Close (X) button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            if (activeTab != KeyboardTab.VARNAMALA) {
                                IconButton(
                                    onClick = {
                                        variantPopup = null
                                        activeTab = KeyboardTab.VARNAMALA
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to Varnamala",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            FilterChip(
                                selected = activeTab == KeyboardTab.VARNAMALA,
                                onClick = {
                                    variantPopup = null
                                    activeTab = KeyboardTab.VARNAMALA
                                },
                                label = {
                                    Text("ವರ್ಣಮಾಲೆ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            )
                            FilterChip(
                                selected = activeTab == KeyboardTab.CLUSTERS,
                                onClick = {
                                    variantPopup = null
                                    activeTab = KeyboardTab.CLUSTERS
                                },
                                label = {
                                    Text(
                                        text = if (knOnly) "ಒತ್ತಕ್ಷರ" else "ಒತ್ತಕ್ಷರ / Clusters",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            )
                            FilterChip(
                                selected = activeTab == KeyboardTab.NUMBERS,
                                onClick = {
                                    variantPopup = null
                                    activeTab = KeyboardTab.NUMBERS
                                },
                                label = {
                                    Text("೧೨೩", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            )
                        }

                        IconButton(
                            onClick = {
                                variantPopup = null
                                onClose()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Keyboard",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Text(
                        text = when (activeTab) {
                            KeyboardTab.VARNAMALA ->
                                if (knOnly) "ಕಾಗುಣಿತ ಮತ್ತು ಒತ್ತಕ್ಷರಗಳಿಗೆ ಯಾವುದೇ ಅಕ್ಷರವನ್ನು ಒತ್ತಿ ಹಿಡಿಯಿರಿ"
                                else "Hold any letter for all Kagunita (ಕಾಗುಣಿತ) & conjunct combinations"
                            KeyboardTab.CLUSTERS ->
                                if (knOnly) "ಒತ್ತಕ್ಷರ ಚಿಹ್ನೆ ಒತ್ತಿರಿ ಅಥವಾ ಸಂಯುಕ್ತಾಕ್ಷರ ಒತ್ತಿ ಹಿಡಿಯಿರಿ"
                                else "Tap subscript to attach, or hold any cluster for ಕಾಗುಣಿತ"
                            KeyboardTab.NUMBERS ->
                                if (knOnly) "ಕನ್ನಡ ಅಂಕಿಗಳು ಮತ್ತು ವಿರಾಮ ಚಿಹ್ನೆಗಳು"
                                else "Kannada numerals, digits & punctuation"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    // Scrollable Key Grid dynamically filling panel height
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        when (activeTab) {
                            KeyboardTab.VARNAMALA -> {
                                KannadaVarnamalaData.swaras.chunked(8).forEach { rowKeys ->
                                    KeyRow(
                                        keys = rowKeys,
                                        isVowelRow = true,
                                        showVariantDot = true,
                                        onTap = { onInsertText(it, false) },
                                        onLongPress = { key ->
                                            variantPopup = VariantPopupState(
                                                rootKey = key,
                                                selectedStem = key,
                                                relatedStems = KannadaVarnamalaData.relatedStemsForKey(key)
                                            )
                                        }
                                    )
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )

                                KannadaVarnamalaData.vargaRows.forEach { rowKeys ->
                                    KeyRow(
                                        keys = rowKeys,
                                        isVowelRow = false,
                                        showVariantDot = true,
                                        onTap = { onInsertText(it, false) },
                                        onLongPress = { key ->
                                            variantPopup = VariantPopupState(
                                                rootKey = key,
                                                selectedStem = key,
                                                relatedStems = KannadaVarnamalaData.relatedStemsForKey(key)
                                            )
                                        }
                                    )
                                }
                            }

                            KeyboardTab.CLUSTERS -> {
                                SectionMiniLabel(
                                    if (knOnly) "ಒತ್ತಕ್ಷರ ಚಿಹ್ನೆಗಳು (ಹಿಂದಿನ ಅಕ್ಷರಕ್ಕೆ ಸೇರುತ್ತದೆ)"
                                    else "Ottakshara Subscripts (attaches to previous letter)"
                                )
                                KannadaVarnamalaData.subscriptModifiers.chunked(7).forEach { chunk ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        chunk.forEach { (display, insertVal) ->
                                            KeyboardKeyButton(
                                                label = if (insertVal == "ರ್") "ರ್" else "◌$display",
                                                isAccent = true,
                                                showVariantDot = false,
                                                onTap = { onInsertText(insertVal, insertVal != "ರ್") },
                                                onLongPress = null,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        repeat(7 - chunk.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 3.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )

                                SectionMiniLabel(
                                    if (knOnly) "ಸಂಯುಕ್ತಾಕ್ಷರಗಳು (ಕಾಗುಣಿತಕ್ಕೆ ಒತ್ತಿ ಹಿಡಿಯಿರಿ)"
                                    else "Consonant Clusters (hold any for ಕಾಗುಣಿತ)"
                                )
                                KannadaVarnamalaData.mixedClusters.chunked(7).forEach { chunk ->
                                    KeyRow(
                                        keys = chunk,
                                        columns = 7,
                                        isVowelRow = false,
                                        showVariantDot = true,
                                        onTap = { onInsertText(it, false) },
                                        onLongPress = { key ->
                                            variantPopup = VariantPopupState(
                                                rootKey = key,
                                                selectedStem = key,
                                                relatedStems = KannadaVarnamalaData.relatedStemsForKey(key)
                                            )
                                        }
                                    )
                                }

                                SectionMiniLabel(
                                    if (knOnly) "ದ್ವಿತ್ವ ಒತ್ತಕ್ಷರಗಳು" else "Doubled Consonants (ಸಜಾತೀಯ)"
                                )
                                KannadaVarnamalaData.doubledClusters.chunked(7).forEach { chunk ->
                                    KeyRow(
                                        keys = chunk,
                                        columns = 7,
                                        isVowelRow = false,
                                        showVariantDot = true,
                                        onTap = { onInsertText(it, false) },
                                        onLongPress = { key ->
                                            variantPopup = VariantPopupState(
                                                rootKey = key,
                                                selectedStem = key,
                                                relatedStems = KannadaVarnamalaData.relatedStemsForKey(key)
                                            )
                                        }
                                    )
                                }

                                SectionMiniLabel(
                                    if (knOnly) "ಅರ್ಕಾವತ್ತು ಮತ್ತು ಅನುಸ್ವಾರ" else "Arkavattu & Nasal Clusters"
                                )
                                KannadaVarnamalaData.arkavattuAndNasalClusters.chunked(7).forEach { chunk ->
                                    KeyRow(
                                        keys = chunk,
                                        columns = 7,
                                        isVowelRow = false,
                                        showVariantDot = true,
                                        onTap = { onInsertText(it, false) },
                                        onLongPress = { key ->
                                            variantPopup = VariantPopupState(
                                                rootKey = key,
                                                selectedStem = key,
                                                relatedStems = KannadaVarnamalaData.relatedStemsForKey(key)
                                            )
                                        }
                                    )
                                }
                            }

                            KeyboardTab.NUMBERS -> {
                                SectionMiniLabel(if (knOnly) "ಕನ್ನಡ ಅಂಕಿಗಳು" else "Kannada Numerals")
                                KannadaVarnamalaData.kannadaNumerals.chunked(5).forEach { chunk ->
                                    KeyRow(
                                        keys = chunk,
                                        columns = 5,
                                        isVowelRow = true,
                                        showVariantDot = false,
                                        onTap = { onInsertText(it, false) },
                                        onLongPress = null
                                    )
                                }
                                SectionMiniLabel(if (knOnly) "ಸಂಖ್ಯೆಗಳು" else "Digits (0–9)")
                                KannadaVarnamalaData.arabicNumerals.chunked(5).forEach { chunk ->
                                    KeyRow(
                                        keys = chunk,
                                        columns = 5,
                                        isVowelRow = false,
                                        showVariantDot = false,
                                        onTap = { onInsertText(it, false) },
                                        onLongPress = null
                                    )
                                }
                                SectionMiniLabel(if (knOnly) "ವಿರಾಮ ಚಿಹ್ನೆಗಳು ಮತ್ತು ವಿಶೇಷ ಚಿಹ್ನೆಗಳು" else "Punctuation & Special Symbols")
                                KannadaVarnamalaData.punctuationSymbols.chunked(7).forEach { chunk ->
                                    KeyRow(
                                        keys = chunk,
                                        columns = 7,
                                        isVowelRow = false,
                                        showVariantDot = false,
                                        onTap = { onInsertText(it, false) },
                                        onLongPress = null
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Pinned Utility Bar + Bottom-Right Corner Resizer Grip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = {
                                variantPopup = null
                                activeTab = if (activeTab == KeyboardTab.CLUSTERS) {
                                    KeyboardTab.VARNAMALA
                                } else {
                                    KeyboardTab.CLUSTERS
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .height(36.dp)
                                .weight(1.3f)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Text(
                                    text = if (activeTab == KeyboardTab.CLUSTERS) "← ವರ್ಣಮಾಲೆ" else "ಒತ್ತಕ್ಷರ",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }

                        Surface(
                            onClick = { onInsertText("್", false) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .height(36.dp)
                                .weight(0.7f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("◌್", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Surface(
                            onClick = { onInsertText(" ", false) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .height(36.dp)
                                .weight(2.0f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.SpaceBar, contentDescription = "Space", modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (knOnly) "ಸ್ಪೇಸ್" else "Space", fontSize = 12.sp)
                            }
                        }

                        Surface(
                            onClick = onBackspace,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .height(36.dp)
                                .weight(0.9f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Backspace",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Surface(
                            onClick = onEnter,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .height(36.dp)
                                .weight(0.9f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardReturn,
                                    contentDescription = "Enter",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Bottom-Right Corner Resizer Grip (width & height)
                        val gripColor = if (isResizing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        }
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isResizing) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                                .pointerInput(
                                    isTabletSideMode,
                                    minAllowedWidth,
                                    maxAllowedWidth,
                                    minAllowedHeight,
                                    maxAllowedHeight
                                ) {
                                    detectDragGestures(
                                        onDragStart = { isResizing = true },
                                        onDragEnd = { isResizing = false },
                                        onDragCancel = { isResizing = false },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val dxDp = with(density) { dragAmount.x.toDp().value }
                                            val dyDp = with(density) { dragAmount.y.toDp().value }
                                            // Left-anchored on tablet -> 1:1 dx tracking; centered on phone -> 2x dx
                                            val widthDelta = if (isTabletSideMode) dxDp else dxDp * 2f
                                            customWidthDp = (customWidthDp + widthDelta).coerceIn(
                                                minAllowedWidth.value,
                                                maxAllowedWidth.value
                                            )
                                            customHeightDp = (customHeightDp - dyDp).coerceIn(
                                                minAllowedHeight.value,
                                                maxAllowedHeight.value
                                            )
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(18.dp)) {
                                val w = size.width
                                val h = size.height
                                val sw = 2.dp.toPx()
                                drawLine(
                                    color = gripColor,
                                    start = Offset(w * 0.20f, h * 0.90f),
                                    end = Offset(w * 0.90f, h * 0.20f),
                                    strokeWidth = sw,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = gripColor,
                                    start = Offset(w * 0.48f, h * 0.90f),
                                    end = Offset(w * 0.90f, h * 0.48f),
                                    strokeWidth = sw,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = gripColor,
                                    start = Offset(w * 0.74f, h * 0.90f),
                                    end = Offset(w * 0.90f, h * 0.74f),
                                    strokeWidth = sw,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                }

                if (isResizing) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .zIndex(30f)
                    ) {
                        Text(
                            text = "${panelWidth.value.roundToInt()} × ${panelHeight.value.roundToInt()} dp",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }

                // Long-Press Kagunita & Special Conjunct Variation Popup
                variantPopup?.let { popup ->
                    val variants = remember(popup.selectedStem) {
                        KannadaVarnamalaData.variantsForStem(popup.selectedStem)
                    }

                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable { variantPopup = null }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shadowElevation = 8.dp,
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = false) {}
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (knOnly) "${popup.selectedStem} — ಕಾಗುಣಿತ ಆಯ್ಕೆಮಾಡಿ"
                                        else "${popup.selectedStem} — Select Variant (ಕಾಗುಣಿತ)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(
                                        onClick = { variantPopup = null },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                                    }
                                }

                                if (popup.relatedStems.size > 1) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        popup.relatedStems.forEach { stem ->
                                            FilterChip(
                                                selected = popup.selectedStem == stem,
                                                onClick = { variantPopup = popup.copy(selectedStem = stem) },
                                                label = {
                                                    Text(
                                                        text = stem,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }

                                val perRow = if (variants.size <= 6) variants.size.coerceAtLeast(4) else 6
                                variants.chunked(perRow).forEach { rowVars ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        rowVars.forEach { variant ->
                                            Surface(
                                                onClick = {
                                                    variantPopup = null
                                                    onInsertText(variant, false)
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(38.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = variant,
                                                        fontSize = if (variant.length >= 4) 15.sp else 17.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        textAlign = TextAlign.Center,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                        repeat(perRow - rowVars.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionMiniLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 1.dp)
    )
}

@Composable
private fun KeyRow(
    keys: List<String>,
    columns: Int = keys.size,
    isVowelRow: Boolean,
    showVariantDot: Boolean,
    onTap: (String) -> Unit,
    onLongPress: ((String) -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keys.forEach { k ->
            KeyboardKeyButton(
                label = k,
                isAccent = isVowelRow,
                showVariantDot = showVariantDot,
                onTap = { onTap(k) },
                onLongPress = onLongPress?.let { cb -> { cb(k) } },
                modifier = Modifier.weight(1f)
            )
        }
        repeat((columns - keys.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyboardKeyButton(
    label: String,
    isAccent: Boolean,
    showVariantDot: Boolean,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val bgColor = if (isAccent) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fgColor = if (isAccent) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bgColor)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress?.let { cb ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        cb()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fgColor,
            fontSize = if (label.length >= 3) 14.sp else 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        if (showVariantDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(3.5.dp)
                    .clip(CircleShape)
                    .background(fgColor.copy(alpha = 0.45f))
            )
        }
    }
}
