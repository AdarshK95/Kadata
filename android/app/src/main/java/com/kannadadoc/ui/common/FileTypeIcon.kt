package com.kannadadoc.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kannadadoc.parser.DocFormat

private data class FileIconStyle(
    val badgeText: String,
    val primaryColor: Color,
    val accentColor: Color,
    val paperTintLight: Color,
    val paperTintDark: Color
)

/**
 * Crisp modern document icon (34dp × 40dp) with distinct format colors and a large,
 * high-legibility full-width format banner tucked cleanly inside the page borders.
 */
@Composable
fun FileTypeIcon(
    format: DocFormat,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val style = when (format) {
        DocFormat.DOCX -> FileIconStyle(
            badgeText = "DOCX",
            primaryColor = Color(0xFF1A5699),
            accentColor = Color(0xFF4A8FE0),
            paperTintLight = Color(0xFFF4F8FE),
            paperTintDark = Color(0xFF142130)
        )
        DocFormat.DOC -> FileIconStyle(
            badgeText = "DOC",
            primaryColor = Color(0xFF3949AB),
            accentColor = Color(0xFF7986CB),
            paperTintLight = Color(0xFFF3F4FD),
            paperTintDark = Color(0xFF1A1E36)
        )
        DocFormat.PDF, DocFormat.PDF_EXTRACTED -> FileIconStyle(
            badgeText = "PDF",
            primaryColor = Color(0xFFC62828),
            accentColor = Color(0xFFEF5350),
            paperTintLight = Color(0xFFFFF5F5),
            paperTintDark = Color(0xFF2E1417)
        )
        DocFormat.RTF -> FileIconStyle(
            badgeText = "RTF",
            primaryColor = Color(0xFF9E6B1E),
            accentColor = Color(0xFFD9A246),
            paperTintLight = Color(0xFFFDF8EE),
            paperTintDark = Color(0xFF2B2110)
        )
        DocFormat.ODT -> FileIconStyle(
            badgeText = "ODT",
            primaryColor = Color(0xFF2E7D32),
            accentColor = Color(0xFF66BB6A),
            paperTintLight = Color(0xFFF2FAF3),
            paperTintDark = Color(0xFF122615)
        )
        DocFormat.TXT -> FileIconStyle(
            badgeText = "TXT",
            primaryColor = Color(0xFF00695C),
            accentColor = Color(0xFF4DB6AC),
            paperTintLight = Color(0xFFF1FAF8),
            paperTintDark = Color(0xFF112926)
        )
        DocFormat.HTML -> FileIconStyle(
            badgeText = "HTML",
            primaryColor = Color(0xFFD84315),
            accentColor = Color(0xFFFF7043),
            paperTintLight = Color(0xFFFFF5F0),
            paperTintDark = Color(0xFF2D170F)
        )
        DocFormat.EPUB -> FileIconStyle(
            badgeText = "EPUB",
            primaryColor = Color(0xFF6A1B9A),
            accentColor = Color(0xFFAB47BC),
            paperTintLight = Color(0xFFFAF2FC),
            paperTintDark = Color(0xFF24122C)
        )
        DocFormat.UNSUPPORTED -> FileIconStyle(
            badgeText = "FILE",
            primaryColor = Color(0xFF607D8B),
            accentColor = Color(0xFF90A4AE),
            paperTintLight = Color(0xFFF5F7F8),
            paperTintDark = Color(0xFF1E2529)
        )
    }

    val paperBg = if (isDarkTheme) style.paperTintDark else style.paperTintLight
    val lineColor = if (isDarkTheme) style.accentColor.copy(alpha = 0.45f) else style.primaryColor.copy(alpha = 0.28f)

    Box(
        modifier = modifier
            .width(34.dp)
            .height(40.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val fold = w * 0.27f
            val r = 3.5.dp.toPx()

            val pagePath = Path().apply {
                moveTo(r, 0f)
                lineTo(w - fold, 0f)
                lineTo(w, fold)
                lineTo(w, h - r)
                quadraticTo(w, h, w - r, h)
                lineTo(r, h)
                quadraticTo(0f, h, 0f, h - r)
                lineTo(0f, r)
                quadraticTo(0f, 0f, r, 0f)
                close()
            }

            drawPath(path = pagePath, color = paperBg)
            drawPath(
                path = pagePath,
                brush = Brush.linearGradient(
                    colors = listOf(style.primaryColor, style.accentColor),
                    start = Offset.Zero,
                    end = Offset(w, h)
                ),
                style = Stroke(width = 1.4.dp.toPx())
            )

            val foldPath = Path().apply {
                moveTo(w - fold, 0f)
                lineTo(w - fold, fold)
                lineTo(w, fold)
                close()
            }
            drawPath(path = foldPath, color = style.accentColor.copy(alpha = 0.35f))

            // Subtle document text lines in upper half above the format banner
            val lineStart = w * 0.18f
            drawLine(
                color = lineColor,
                start = Offset(lineStart, h * 0.24f),
                end = Offset(w * 0.62f, h * 0.24f),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = lineColor,
                start = Offset(lineStart, h * 0.39f),
                end = Offset(w * 0.82f, h * 0.39f),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Full-width high-contrast format banner spanning inside the icon borders
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, bottom = 2.5.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(style.primaryColor, style.accentColor)
                    ),
                    shape = RoundedCornerShape(3.5.dp)
                )
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = style.badgeText,
                color = Color.White,
                fontSize = if (style.badgeText.length >= 4) 8.8.sp else 10.2.sp,
                lineHeight = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = if (style.badgeText.length >= 4) (-0.3).sp else 0.1.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
