package com.kannadadoc.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kannadadoc.R

private const val KADATA_GITHUB_URL = "https://github.com/AdarshK95/Kadata"

@Composable
fun AboutKadataDialog(
    knOnly: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val versionName = stringResource(id = R.string.app_version)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.kadata_logo_icon),
                    contentDescription = "Kadata",
                    modifier = Modifier.size(40.dp)
                )
                Column {
                    Text(
                        text = "Kadata • ಕಡತ",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Version $versionName • ಆವೃತ್ತಿ $versionName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. DEVELOPER & GITHUB SECTION (First)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Developer • ಡೆವಲಪರ್",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Adarsh Katagihallimath",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(KADATA_GITHUB_URL)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "GitHub Repository & Docs • GitHub ರೆಪೊಸಿಟರಿ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = KADATA_GITHUB_URL,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // 2. KANNADA SECTION (Always shown above English)
                Text(
                    text = "ಕನ್ನಡ — ಕಡತ ಆಪ್ ಬಗ್ಗೆ",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "ಕಡತ (Kadata) ಆಂಡ್ರಾಯ್ಡ್ ಫೋನ್ ಮತ್ತು ಟ್ಯಾಬ್ಲೆಟ್‌ಗಳಿಗಾಗಿ ನಿರ್ಮಿಸಲಾದ 100% ಆಫ್‌ಲೈನ್ ಕನ್ನಡ ದಾಖಲೆ ಓದುಗ, ಸಂಪಾದಕ ಮತ್ತು ಪರಿವರ್ತಕ ಆಪ್ ಆಗಿದೆ. ಇದು DOC, DOCX, PDF, RTF ಮತ್ತು TXT ಫೈಲ್‌ಗಳಲ್ಲಿನ ನುಡಿ, ಬರಹ ಮತ್ತು ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ ಫಾರ್ಮ್ಯಾಟ್‌ಗಳನ್ನು ಸ್ವಯಂಚಾಲಿತವಾಗಿ ಗುರುತಿಸಿ ಸುಗಮವಾಗಿ ತೋರಿಸುತ್ತದೆ. ಒಳನಿರ್ಮಿತ ವರ್ಣಮಾಲೆ ಮತ್ತು ಹಳೆಗನ್ನಡ ಕೀಬೋರ್ಡ್, Gboard ಅಥವಾ ಧ್ವನಿ ಟೈಪಿಂಗ್ ಮೂಲಕ ಸುಲಭವಾಗಿ ತಿದ್ದುಪಡಿ ಮಾಡಿ, ನುಡಿ/ಬರಹ (PC) ಅಥವಾ ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡದಲ್ಲಿ ಉಳಿಸಬಹುದು.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "ಗೌಪ್ಯತೆ ಮತ್ತು ಸುರಕ್ಷತೆ",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "• 100% ಆಫ್‌ಲೈನ್ — ಎಲ್ಲಾ ದಾಖಲೆಗಳು ಮತ್ತು ಡೇಟಾ ನಿಮ್ಮ ಸಾಧನದಲ್ಲಿಯೇ ಸುರಕ್ಷಿತವಾಗಿರುತ್ತವೆ.\n" +
                                "• ನಾವು ನಿಮ್ಮ ಫೈಲ್‌ಗಳನ್ನು ಓದುವುದಿಲ್ಲ, ಸಂಗ್ರಹಿಸುವುದಿಲ್ಲ ಅಥವಾ ಯಾವುದೇ ಸರ್ವರ್‌ಗೆ ಕಳುಹಿಸುವುದಿಲ್ಲ.\n" +
                                "• ಯಾವುದೇ ಜಾಹೀರಾತುಗಳು, ಟ್ರ್ಯಾಕಿಂಗ್ ಅಥವಾ ಬಾಹ್ಯ ಡೇಟಾ ಹಂಚಿಕೆ ಇಲ್ಲ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                HorizontalDivider()

                // 3. ENGLISH SECTION (Shown below Kannada)
                Text(
                    text = "English — About Kadata",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Kadata (ಕಡತ) is a 100% offline Kannada document reader, editor, and converter built for Android phones and tablets. It automatically detects Nudi, Baraha, and Modern Universal Kannada across DOC, DOCX, PDF, RTF, and TXT files, lets you edit comfortably with voice typing, Gboard, or the built-in Varnamala & Halekannada keyboard, and exports cleanly back to PC-ready Nudi/Baraha or Modern Universal Kannada.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Privacy & Content Safety",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "• 100% On-Device & Offline — everything stays strictly on your phone or tablet.\n" +
                                "• We never read your files, collect your text, or upload any of your data.\n" +
                                "• Zero ads, zero tracking, and zero cloud servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(if (knOnly) "ಮುಚ್ಚಿ" else "Close • ಮುಚ್ಚಿ")
            }
        }
    )
}
