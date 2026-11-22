package com.kannadadoc.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.KeyboardHide
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
import androidx.compose.ui.draw.shadow
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
    SUBSCRIPTS,
    NUMBERS
}

private enum class PopupSectionTab {
    KAGUNITA,
    OTTAKSHARA
}

private data class VariantPopupState(
    val rootKey: String,
    val selectedStem: String,
    val activePopupTab: PopupSectionTab = PopupSectionTab.KAGUNITA
)

private object KannadaVarnamalaData {
    // All 34 standard Kannada consonants in Varga + Avargiya order (used to map all 34 consonant+consonant pairs)
    val all34Consonants: List<String> = listOf(
        "ಕ", "ಖ", "ಗ", "ಘ", "ಙ",
        "ಚ", "ಛ", "ಜ", "ಝ", "ಞ",
        "ಟ", "ಠ", "ಡ", "ಢ", "ಣ",
        "ತ", "ಥ", "ದ", "ಧ", "ನ",
        "ಪ", "ಫ", "ಬ", "ಭ", "ಮ",
        "ಯ", "ರ", "ಲ", "ವ", "ಶ", "ಷ", "ಸ", "ಹ", "ಳ"
    )

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

    // ONLY Ottakshara Subscripts that attach to the previous letter (34 subscripts + Arkavattu ರ್)
    // Organized cleanly: most frequent subscripts first, then complete Varga order.
    val frequentSubscripts: List<Pair<String, String>> = listOf(
        "್ರ" to "್ರ", "್ಯ" to "್ಯ", "್ವ" to "್ವ", "್ನ" to "್ನ", "್ಮ" to "್ಮ", "್ತ" to "್ತ", "ರ್" to "ರ್"
    )

    val allSubscriptsInVargaOrder: List<Pair<String, String>> =
        all34Consonants.map { c -> "್$c" to "್$c" }

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

    // Priority literary / special conjuncts per letter (including 3-consonant clusters like ಕ್ಷ್ಮ, ತ್ರ್ಯ, ಷ್ಟ್ರ, ಸ್ತ್ರ, ನ್ದ್ರ)
    private val specialClustersForConsonant: Map<String, List<String>> = mapOf(
        "ಕ" to listOf("ಕ್ಕ", "ಕ್ಷ", "ಕ್ರ", "ಕ್ಯ", "ಕ್ತ", "ಕ್ಮ", "ಕ್ವ", "ಕ್ಲ", "ಕ್ನ", "ಕ್ಷ್ಮ", "ಕ್ಷ್ಯ", "ರ್ಕ", "ಂಕ"),
        "ಖ" to listOf("ಖ್ಖ", "ಖ್ಯ", "ಖ್ರ", "ಖ್ವ", "ರ್ಖ", "ಂಖ"),
        "ಗ" to listOf("ಗ್ಗ", "ಗ್ರ", "ಗ್ಯ", "ಗ್ನ", "ಗ್ಧ", "ಗ್ಮ", "ಗ್ವ", "ಗ್ಲ", "ರ್ಗ", "ಂಗ"),
        "ಘ" to listOf("ಘ್ಘ", "ಘ್ರ", "ಘ್ಯ", "ಘ್ನ", "ರ್ಘ", "ಂಘ"),
        "ಙ" to listOf("ಙ್ಕ", "ಙ್ಗ", "ಙ್ಖ", "ಙ್ಘ", "ಙ್ಙ"),
        "ಚ" to listOf("ಚ್ಚ", "ಚ್ಛ", "ಚ್ಯ", "ಚ್ರ", "ಚ್ವ", "ರ್ಚ", "ಂಚ"),
        "ಛ" to listOf("ಛ್ಛ", "ಛ್ಯ", "ಛ್ರ", "ಛ್ವ", "ರ್ಛ", "ಂಛ"),
        "ಜ" to listOf("ಜ್ಜ", "ಜ್ಞ", "ಜ್ರ", "ಜ್ಯ", "ಜ್ವ", "ಜ್ಮ", "ರ್ಜ", "ಂಜ"),
        "ಝ" to listOf("ಝ್ಝ", "ಝ್ಯ", "ಝ್ರ", "ರ್ಝ", "ಂಝ"),
        "ಞ" to listOf("ಞ್ಚ", "ಞ್ಜ", "ಞ್ಛ", "ಞ್ಞ"),
        "ಟ" to listOf("ಟ್ಟ", "ಟ್ರ", "ಟ್ಯ", "ಟ್ವ", "ಟ್ಮ", "ರ್ಟ", "ಂಟ"),
        "ಠ" to listOf("ಠ್ಠ", "ಠ್ಯ", "ಠ್ರ", "ರ್ಠ", "ಂಠ"),
        "ಡ" to listOf("ಡ್ಡ", "ಡ್ರ", "ಡ್ಯ", "ಡ್ವ", "ಡ್ಮ", "ರ್ಡ", "ಂಡ"),
        "ಢ" to listOf("ಢ್ಢ", "ಢ್ಯ", "ಢ್ರ", "ರ್ಢ", "ಂಢ"),
        "ಣ" to listOf("ಣ್ಣ", "ಣ್ಯ", "ಣ್ವ", "ಣ್ಮ", "ಣ್ಟ", "ಣ್ಡ", "ರ್ಣ", "ಂಣ"),
        "ತ" to listOf("ತ್ತ", "ತ್ರ", "ತ್ರ್ಯ", "ತ್ಯ", "ತ್ವ", "ತ್ಸ", "ತ್ನ", "ತ್ಮ", "ತ್ಪ", "ತ್ಕ", "ರ್ತ", "ಂತ"),
        "ಥ" to listOf("ಥ್ಥ", "ಥ್ಯ", "ಥ್ರ", "ಥ್ವ", "ರ್ಥ", "ಂಥ"),
        "ದ" to listOf("ದ್ದ", "ದ್ರ", "ದ್ಯ", "ದ್ವ", "ದ್ಧ", "ದ್ಮ", "ದ್ಗ", "ದ್ಭ", "ದ್ಬ", "ರ್ದ", "ಂದ"),
        "ಧ" to listOf("ದ್ಧ", "ಧ್ರ", "ಧ್ಯ", "ಧ್ವ", "ಧ್ಮ", "ಧ್ನ", "ರ್ಧ", "ಂಧ"),
        "ನ" to listOf("ನ್ನ", "ನ್ಯ", "ನ್ವ", "ನ್ಮ", "ನ್ತ", "ನ್ದ", "ನ್ದ್ರ", "ನ್ಸ", "ರ್ನ", "ಂನ"),
        "ಪ" to listOf("ಪ್ಪ", "ಪ್ರ", "ಪ್ಯ", "ಪ್ತ", "ಪ್ಲ", "ಪ್ವ", "ಪ್ನ", "ಪ್ಮ", "ರ್ಪ", "ಂಪ"),
        "ಫ" to listOf("ಫ್ಫ", "ಫ್ರ", "ಫ್ಯ", "ಫ್ಲ", "ರ್ಫ", "ಂಫ"),
        "ಬ" to listOf("ಬ್ಬ", "ಬ್ರ", "ಬ್ಯ", "ಬ್ದ", "ಬ್ಜ", "ಬ್ಧ", "ಬ್ಲ", "ಬ್ವ", "ರ್ಬ", "ಂಬ"),
        "ಭ" to listOf("ಭ್ಭ", "ಭ್ರ", "ಭ್ಯ", "ಭ್ವ", "ರ್ಭ", "ಂಭ"),
        "ಮ" to listOf("ಮ್ಮ", "ಮ್ರ", "ಮ್ಯ", "ಮ್ಲ", "ಮ್ವ", "ಮ್ನ", "ಮ್ಪ", "ಮ್ಬ", "ರ್ಮ", "ಂಮ"),
        "ಯ" to listOf("ಯ್ಯ", "ಯ್ರ", "ಯ್ವ", "ರ್ಯ", "ಂಯ"),
        "ರ" to listOf("ರ್ರ", "ರ್ಯ", "ರ್ವ", "ರ್ಮ", "ರ್ತ", "ರ್ದ", "ರ್ಕ", "ರ್ನ", "ರ್ಪ", "ರ್ಸ"),
        "ಲ" to listOf("ಲ್ಲ", "ಲ್ಯ", "ಲ್ವ", "ಲ್ಕ", "ಲ್ಪ", "ಲ್ಮ", "ಲ್ಗ", "ರ್ಲ", "ಂಲ"),
        "ವ" to listOf("ವ್ವ", "ವ್ರ", "ವ್ಯ", "ವ್ಲ", "ವ್ನ", "ರ್ವ", "ಂವ"),
        "ಶ" to listOf("ಶ್ಶ", "ಶ್ರ", "ಶ್ಯ", "ಶ್ವ", "ಶ್ಚ", "ಶ್ಲ", "ಶ್ನ", "ಶ್ಮ", "ರ್ಶ", "ಂಶ"),
        "ಷ" to listOf("ಷ್ಷ", "ಷ್ಟ", "ಷ್ಠ", "ಷ್ಟ್ರ", "ಷ್ಯ", "ಷ್ಮ", "ಷ್ಣ", "ಷ್ಪ", "ಷ್ಕ", "ಷ್ವ", "ರ್ಷ", "ಂಷ"),
        "ಸ" to listOf("ಸ್ಸ", "ಸ್ತ", "ಸ್ಥ", "ಸ್ತ್ರ", "ಸ್ರ", "ಸ್ವ", "ಸ್ಮ", "ಸ್ನ", "ಸ್ಪ", "ಸ್ಯ", "ಸ್ಕ", "ಸ್ಲ", "ರ್ಸ", "ಂಸ"),
        "ಹ" to listOf("ಹ್ಹ", "ಹ್ರ", "ಹ್ಮ", "ಹ್ಯ", "ಹ್ವ", "ಹ್ನ", "ಹ್ಲ", "ರ್ಹ", "ಂಹ"),
        "ಳ" to listOf("ಳ್ಳ", "ಳ್ಯ", "ಳ್ವ", "ಳ್ಮ", "ರ್ಳ", "ಂಳ")
    )

    fun isConsonantKey(key: String): Boolean = !vowelVariantsMap.containsKey(key)

    /**
     * Quick-switch stem chips shown at the top of the Kagunita tab for a letter (base + top clusters).
     */
    fun quickStemsForLetter(rootKey: String, selectedStem: String): List<String> {
        if (!isConsonantKey(rootKey)) return listOf(rootKey)
        val list = linkedSetOf(rootKey)
        if (selectedStem != rootKey) list.add(selectedStem)
        specialClustersForConsonant[rootKey]?.let { list.addAll(it) }
            ?: run {
                if (rootKey.length == 1) {
                    list.add("${rootKey}್${rootKey}")
                    list.add("ರ್$rootKey")
                    list.add("ಂ$rootKey")
                }
            }
        return list.toList()
    }

    /**
     * Special / Doubled / Arkavattu / Nasal clusters for a consonant (shown in Section 1 of Ottakshara tab).
     */
    fun featuredClustersForLetter(rootKey: String): List<String> {
        if (!isConsonantKey(rootKey)) return emptyList()
        specialClustersForConsonant[rootKey]?.let { return it }
        return if (rootKey.length == 1) {
            listOf("${rootKey}್${rootKey}", "ರ್$rootKey", "ಂ$rootKey", "${rootKey}್ರ", "${rootKey}್ಯ", "${rootKey}್ವ")
        } else {
            listOf("ರ್$rootKey", "ಂ$rootKey", "${rootKey}್ರ", "${rootKey}್ಯ")
        }
    }

    /**
     * Complete 34-consonant ottakshara matrix for ANY consonant X: X + ್ + every Kannada consonant (ಕ..ಳ).
     * For example, for ಕ -> ಕ್ಕ, ಕ್ಖ, ಕ್ಗ, ... ಕ್ಮ, ಕ್ಯ, ಕ್ರ, ಕ್ಲ, ಕ್ವ, ಕ್ಶ, ಕ್ಷ, ಕ್ಸ, ಕ್ಹ, ಕ್ಳ.
     */
    fun all34ConjunctsForLetter(rootKey: String): List<String> {
        if (!isConsonantKey(rootKey)) return emptyList()
        return all34Consonants.map { c2 -> "${rootKey}್${c2}" }
    }

    /**
     * Full canonical Kagunita (17 forms) for any consonant or conjunct stem:
     * X, X್, Xಾ, Xಿ, Xೀ, Xು, Xೂ, Xೃ, Xೄ, Xೆ, Xೇ, Xೈ, Xೊ, Xೋ, Xೌ, Xಂ, Xಃ
     */
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
 * Draggable 4-corner dual floating buttons rendered strictly inside the editor canvas Box
 * ONLY while the built-in Kannada keyboard is closed:
 * - Top button ("ಕ"): opens Kadata's built-in Kannada Varnamala keyboard.
 * - Bottom button (⌨️): ALWAYS shown right below "ಕ":
 *     • Tap = toggles the default system keyboard open / closed.
 *     • Long-press = opens Android's Input Method Picker so the user can switch keyboards.
 * Both buttons drag and snap together to any of the 4 corners (BL / BR / TL / TR).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingKeyboardActionButtons(
    anchor: KeyboardAnchor,
    isSystemKeyboardOpen: Boolean,
    onAnchorChange: (KeyboardAnchor) -> Unit,
    onOpenKannadaKeyboard: () -> Unit,
    onToggleSystemKeyboard: () -> Unit,
    onPickSystemKeyboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val boxWidthPx = with(density) { maxWidth.toPx() }
        val boxHeightPx = with(density) { maxHeight.toPx() }
        val buttonWidthDp = 44.dp
        val gapDp = 8.dp
        val stackHeightDp = buttonWidthDp * 2 + gapDp
        val stackWidthPx = with(density) { buttonWidthDp.toPx() }
        val stackHeightPx = with(density) { stackHeightDp.toPx() }
        val marginPx = with(density) { 12.dp.toPx() }

        val anchorX = when (anchor) {
            KeyboardAnchor.BOTTOM_LEFT, KeyboardAnchor.TOP_LEFT -> marginPx
            KeyboardAnchor.BOTTOM_RIGHT, KeyboardAnchor.TOP_RIGHT ->
                (boxWidthPx - stackWidthPx - marginPx).coerceAtLeast(marginPx)
        }
        val anchorY = when (anchor) {
            KeyboardAnchor.TOP_LEFT, KeyboardAnchor.TOP_RIGHT -> marginPx
            KeyboardAnchor.BOTTOM_LEFT, KeyboardAnchor.BOTTOM_RIGHT ->
                (boxHeightPx - stackHeightPx - marginPx).coerceAtLeast(marginPx)
        }

        val currentX = if (isDragging) dragX else anchorX
        val currentY = if (isDragging) dragY else anchorY

        val btnShape = RoundedCornerShape(12.dp)
        val borderColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)

        Column(
            verticalArrangement = Arrangement.spacedBy(gapDp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
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
                                (boxWidthPx - stackWidthPx - marginPx).coerceAtLeast(marginPx)
                            )
                            dragY = (dragY + dragAmount.y).coerceIn(
                                marginPx,
                                (boxHeightPx - stackHeightPx - marginPx).coerceAtLeast(marginPx)
                            )
                        },
                        onDragEnd = {
                            val centerX = dragX + stackWidthPx / 2f
                            val centerY = dragY + stackHeightPx / 2f
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
            // Top Button: Kannada Letter 'ಕ' -> Opens Kadata Kannada Varnamala Keyboard
            Box(
                modifier = Modifier
                    .size(buttonWidthDp)
                    .shadow(6.dp, btnShape)
                    .clip(btnShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(1.dp, borderColor, btnShape)
                    .clickable(onClick = onOpenKannadaKeyboard),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ಕ",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            // Bottom Button: System Keyboard Trigger
            // • Tap = toggle default OS keyboard open / closed
            // • Long-press = open Android's Input Method Picker (switch keyboards)
            val sysBg = if (isSystemKeyboardOpen) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.secondary
            }
            val sysFg = if (isSystemKeyboardOpen) {
                MaterialTheme.colorScheme.onTertiary
            } else {
                MaterialTheme.colorScheme.onSecondary
            }

            Box(
                modifier = Modifier
                    .size(buttonWidthDp)
                    .shadow(6.dp, btnShape)
                    .clip(btnShape)
                    .background(sysBg)
                    .border(1.dp, borderColor, btnShape)
                    .combinedClickable(
                        onClick = onToggleSystemKeyboard,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPickSystemKeyboard()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSystemKeyboardOpen) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                    contentDescription = if (isSystemKeyboardOpen) {
                        "Close System Keyboard (Long-press to switch)"
                    } else {
                        "Open System Keyboard (Long-press to switch)"
                    },
                    tint = sysFg,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

/**
 * Adaptive Kannada Varnamala & Ottakshara Subscripts Keyboard Pane.
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
        minOf(maxAllowedWidth, 420.dp)
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
                    // Header: Back arrow (when in Subscripts/Numbers) + Tabs + Close (X)
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
                                selected = activeTab == KeyboardTab.SUBSCRIPTS,
                                onClick = {
                                    variantPopup = null
                                    activeTab = KeyboardTab.SUBSCRIPTS
                                },
                                label = {
                                    Text(
                                        text = if (knOnly) "ಒತ್ತಕ್ಷರ" else "ಒತ್ತಕ್ಷರ (Subscripts)",
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
                                if (knOnly) "ಕಾಗುಣಿತ ಮತ್ತು ಎಲ್ಲಾ ಒತ್ತಕ್ಷರ ಸಂಯೋಜನೆಗಳಿಗೆ ಅಕ್ಷರವನ್ನು ಒತ್ತಿ ಹಿಡಿಯಿರಿ"
                                else "Hold any letter for Kagunita (ಕಾಗುಣಿತ) & all 34+ Ottakshara combinations"
                            KeyboardTab.SUBSCRIPTS ->
                                if (knOnly) "ಹಿಂದಿನ ಅಕ್ಷರಕ್ಕೆ ಒತ್ತಕ್ಷರ ಸೇರಿಸಲು ಕೆಳಗಿನ ಚಿಹ್ನೆ ಒತ್ತಿರಿ"
                                else "Tap any subscript to attach directly to the previous letter"
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

                    // Scrollable Key Grid Body
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
                                                activePopupTab = PopupSectionTab.KAGUNITA
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
                                                activePopupTab = PopupSectionTab.KAGUNITA
                                            )
                                        }
                                    )
                                }
                            }

                            // ONLY Attaching Ottakshara Subscripts here (clusters/doubled/arkavattu live in each letter's long-press!)
                            KeyboardTab.SUBSCRIPTS -> {
                                SectionMiniLabel(
                                    if (knOnly) "ಪ್ರಮುಖ ಒತ್ತಕ್ಷರ ಚಿಹ್ನೆಗಳು"
                                    else "Frequent Subscripts (attaches to previous letter)"
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    KannadaVarnamalaData.frequentSubscripts.forEach { (display, insertVal) ->
                                        KeyboardKeyButton(
                                            label = if (insertVal == "ರ್") "ರ್" else "◌$display",
                                            isAccent = true,
                                            showVariantDot = false,
                                            onTap = { onInsertText(insertVal, insertVal != "ರ್") },
                                            onLongPress = null,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )

                                SectionMiniLabel(
                                    if (knOnly) "ಎಲ್ಲಾ 34 ಒತ್ತಕ್ಷರ ಚಿಹ್ನೆಗಳು (ವರ್ಗ ಕ್ರಮ)"
                                    else "All 34 Ottakshara Subscripts (Varga order)"
                                )
                                KannadaVarnamalaData.allSubscriptsInVargaOrder.chunked(7).forEach { chunk ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        chunk.forEach { (display, insertVal) ->
                                            KeyboardKeyButton(
                                                label = "◌$display",
                                                isAccent = false,
                                                showVariantDot = false,
                                                onTap = { onInsertText(insertVal, true) },
                                                onLongPress = null,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        repeat(7 - chunk.size) { Spacer(Modifier.weight(1f)) }
                                    }
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
                        // Clean non-clipping switch button (Varnamala <-> Subscripts)
                        Surface(
                            onClick = {
                                variantPopup = null
                                activeTab = if (activeTab == KeyboardTab.SUBSCRIPTS) {
                                    KeyboardTab.VARNAMALA
                                } else {
                                    KeyboardTab.SUBSCRIPTS
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .height(36.dp)
                                .weight(1.45f)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Text(
                                    text = if (activeTab == KeyboardTab.SUBSCRIPTS) "ವರ್ಣಮಾಲೆ" else "ಒತ್ತಕ್ಷರ",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
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
                                .weight(0.68f)
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
                                .weight(1.9f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.SpaceBar, contentDescription = "Space", modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (knOnly) "ಸ್ಪೇಸ್" else "Space", fontSize = 12.sp, maxLines = 1)
                            }
                        }

                        Surface(
                            onClick = onBackspace,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .height(36.dp)
                                .weight(0.88f)
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
                                .weight(0.88f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardReturn,
                                    contentDescription = "Enter",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Bottom-Right Corner Resizer Grip
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

                // =============================================================
                // COMPLETE PER-LETTER LONG-PRESS POPUP
                // Tab 1: ಕಾಗುಣಿತ (all 17 vowel forms for base or any chosen cluster)
                // Tab 2: ಒತ್ತಕ್ಷರ (special + doubled + arkavattu + nasal + ALL 34 X+್+C!)
                // =============================================================
                variantPopup?.let { popup ->
                    val isConsonant = remember(popup.rootKey) {
                        KannadaVarnamalaData.isConsonantKey(popup.rootKey)
                    }
                    val quickStems = remember(popup.rootKey, popup.selectedStem) {
                        KannadaVarnamalaData.quickStemsForLetter(popup.rootKey, popup.selectedStem)
                    }
                    val variants = remember(popup.selectedStem) {
                        KannadaVarnamalaData.variantsForStem(popup.selectedStem)
                    }
                    val featuredClusters = remember(popup.rootKey) {
                        KannadaVarnamalaData.featuredClustersForLetter(popup.rootKey)
                    }
                    val all34Conjuncts = remember(popup.rootKey) {
                        KannadaVarnamalaData.all34ConjunctsForLetter(popup.rootKey)
                    }

                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.50f))
                            .clickable { variantPopup = null }
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shadowElevation = 8.dp,
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(enabled = false) {}
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                // Popup Header: Title + Segmented Tabs (ಕಾಗುಣಿತ / ಒತ್ತಕ್ಷರ) + Close
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = popup.selectedStem,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (isConsonant) {
                                            FilterChip(
                                                selected = popup.activePopupTab == PopupSectionTab.KAGUNITA,
                                                onClick = {
                                                    variantPopup = popup.copy(activePopupTab = PopupSectionTab.KAGUNITA)
                                                },
                                                label = {
                                                    Text("ಕಾಗುಣಿತ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            )
                                            FilterChip(
                                                selected = popup.activePopupTab == PopupSectionTab.OTTAKSHARA,
                                                onClick = {
                                                    variantPopup = popup.copy(activePopupTab = PopupSectionTab.OTTAKSHARA)
                                                },
                                                label = {
                                                    Text(
                                                        text = if (knOnly) "ಒತ್ತಕ್ಷರ (${all34Conjuncts.size}+)"
                                                        else "ಒತ್ತಕ್ಷರ / All (${all34Conjuncts.size}+)",
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { variantPopup = null },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                                    }
                                }

                                // Scrollable Popup Content
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (!isConsonant || popup.activePopupTab == PopupSectionTab.KAGUNITA) {
                                        // Quick stem chips (base + doubled + special + arkavattu + nasal)
                                        if (isConsonant && quickStems.size > 1) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                quickStems.forEach { stem ->
                                                    FilterChip(
                                                        selected = popup.selectedStem == stem,
                                                        onClick = { variantPopup = popup.copy(selectedStem = stem) },
                                                        label = {
                                                            Text(
                                                                text = stem,
                                                                fontSize = 14.sp,
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
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                                                                fontSize = if (variant.length >= 4) 14.sp else 16.sp,
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
                                    } else {
                                        // TAB 2: ALL Ottakshara Combinations for popup.rootKey
                                        Text(
                                            text = if (knOnly) "ಸೇರಿಸಲು ಒತ್ತಿರಿ • ಕಾಗುಣಿತ ನೋಡಲು ಒತ್ತಿ ಹಿಡಿಯಿರಿ"
                                            else "Tap to insert • Hold any cluster for all 17 ಕಾಗುಣಿತ forms",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 2.dp)
                                        )

                                        SectionMiniLabel(
                                            if (knOnly) "ವಿಶೇಷ, ದ್ವಿತ್ವ, ಅರ್ಕಾವತ್ತು ಮತ್ತು ಅನುಸ್ವಾರ"
                                            else "Special, Doubled, Arkavattu & Nasal"
                                        )
                                        featuredClusters.chunked(6).forEach { chunk ->
                                            KeyRow(
                                                keys = chunk,
                                                columns = 6,
                                                isVowelRow = true,
                                                showVariantDot = true,
                                                onTap = { chosen ->
                                                    variantPopup = null
                                                    onInsertText(chosen, false)
                                                },
                                                onLongPress = { chosen ->
                                                    variantPopup = popup.copy(
                                                        selectedStem = chosen,
                                                        activePopupTab = PopupSectionTab.KAGUNITA
                                                    )
                                                }
                                            )
                                        }

                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        )

                                        SectionMiniLabel(
                                            if (knOnly) "${popup.rootKey} + ಎಲ್ಲಾ 34 ವ್ಯಂಜನ ಒತ್ತಕ್ಷರಗಳು"
                                            else "All 34 ${popup.rootKey} + Consonant Combinations"
                                        )
                                        all34Conjuncts.chunked(6).forEach { chunk ->
                                            KeyRow(
                                                keys = chunk,
                                                columns = 6,
                                                isVowelRow = false,
                                                showVariantDot = true,
                                                onTap = { chosen ->
                                                    variantPopup = null
                                                    onInsertText(chosen, false)
                                                },
                                                onLongPress = { chosen ->
                                                    variantPopup = popup.copy(
                                                        selectedStem = chosen,
                                                        activePopupTab = PopupSectionTab.KAGUNITA
                                                    )
                                                }
                                            )
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
