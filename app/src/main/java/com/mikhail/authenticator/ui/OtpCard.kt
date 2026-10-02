package com.mikhail.authenticator.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mikhail.authenticator.data.StoredAccount
import com.mikhail.authenticator.icons.IssuerIcons

/**
 * One account card, two rows (spec §2.3): the issuer name owns the top row across the full
 * width, the bottom row carries the icon inside the countdown ring next to the current code.
 * Tapping anywhere copies the code.
 *
 * The seconds appear next to the name only in the last ten seconds, but their slot is reserved
 * at all times — the top row keeps the same length whether they are shown or not, so nothing
 * jumps and nothing wraps when they arrive. The ring turns amber at ten seconds and red at
 * five, and the seconds are printed next to the name: the warning never rests on colour alone.
 *
 * The code is never truncated: its size is fitted to the width actually left in the bottom row
 * **and divided by the system font scale**, because `maxWidth` arrives in dp while text is
 * drawn in sp. Without that division a phone with a larger system font overflows the card and
 * the trailing digits disappear.
 *
 * [compact] — режим узкой карточки (две колонки в портрете, ландшафт): плитка и кольцо
 * уменьшаются, строка логина скрывается, имя подрезается многоточием.
 */
@Composable
fun OtpCard(
    account: StoredAccount,
    code: String,
    secondsRemaining: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onCopy: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val fraction = (secondsRemaining.toFloat() / account.period.toFloat()).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(targetValue = fraction, label = "period-progress")
    val soon = secondsRemaining <= 10
    val ringColor = when {
        secondsRemaining <= 5 -> Color(0xFFD32F2F)
        soon -> Color(0xFFE6A700)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        shape = RoundedCornerShape(if (compact) 14.dp else 18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            // Верхняя строка: имя на всю ширину карточки, место под секунды зарезервировано
            // всегда — длина строки не меняется, когда секунды появляются.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = account.issuer,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (soon) "$secondsRemaining с" else "",
                    color = ringColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 11.sp else 12.sp,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = if (compact) 30.dp else 34.dp),
                )
            }

            // В узкой карточке логин скрыт целиком: режем имя, а не код.
            if (!compact) {
                Text(
                    text = account.account,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Нижняя строка: кольцо отсчёта вокруг иконки и сам код.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(if (compact) 30.dp else 38.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { animatedFraction },
                        modifier = Modifier.fillMaxSize(),
                        color = ringColor,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        strokeWidth = if (compact) 2.5.dp else 3.dp,
                        strokeCap = StrokeCap.Round,
                    )
                    Box(
                        modifier = Modifier
                            .size(if (compact) 22.dp else 28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        IssuerIcon(
                            issuer = account.issuer,
                            monogramSize = if (compact) 9.sp else 12.sp,
                            modifier = Modifier.size(if (compact) 14.dp else 18.dp),
                        )
                    }
                }

                // Кегль подбирается по фактически оставшейся ширине в dp и делится на системный
                // масштаб шрифта: текст рисуется в sp, а maxWidth приходит в dp — без деления
                // цифры уезжают за край карточки на телефоне с крупным системным шрифтом.
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (compact) 8.dp else 10.dp),
                ) {
                    val formatted = formatCode(code)
                    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(0.5f)
                    val fit = maxWidth.value / (0.62f * formatted.length.coerceAtLeast(1)) / fontScale
                    Text(
                        text = formatted,
                        fontSize = fit
                            .coerceAtMost(if (compact) 24f else 28f)
                            .coerceAtLeast(11f)
                            .sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** `382910` → `382 910` — easier to read aloud and to type. */
internal fun formatCode(code: String): String = when (code.length) {
    6 -> "${code.substring(0, 3)} ${code.substring(3)}"
    8 -> "${code.substring(0, 4)} ${code.substring(4)}"
    else -> code
}

@Composable
private fun IssuerIcon(issuer: String, monogramSize: TextUnit = 20.sp, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(IssuerIcons.monogramColor(issuer)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = IssuerIcons.monogram(issuer),
                color = Color.White,
                fontSize = monogramSize,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    val url = IssuerIcons.faviconUrl(issuer)
    if (url == null) {
        fallback()
    } else {
        // Favicon when reachable, monogram when offline or unknown — the list is never iconless.
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            modifier = modifier
                .clip(CircleShape)
                .aspectRatio(1f),
            loading = { fallback() },
            error = { fallback() },
        )
    }
}
