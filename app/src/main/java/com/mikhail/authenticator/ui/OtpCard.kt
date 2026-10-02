package com.mikhail.authenticator.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mikhail.authenticator.data.StoredAccount
import com.mikhail.authenticator.icons.IssuerIcons

/**
 * One account card: the issuer icon in a circular tile, the current code, and the 30-second
 * countdown drawn as a ring around that tile. Tapping anywhere copies the code (spec §2.3).
 *
 * The ring is deliberately not the only expiry cue: from ten seconds left the remaining
 * seconds appear next to the name and the ring turns amber, from five — red, so the warning
 * never rests on colour alone.
 *
 * [compact] — режим узкой карточки (две колонки в портрете, ландшафт): плитка и кольцо
 * уменьшаются, строка логина скрывается, имя подрезается многоточием. Код не обрезается
 * никогда: кегль подбирается по фактической ширине (см. ниже).
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Кольцо вокруг иконки: отсчёт висит на том элементе, на который глаз и так
            // смотрит, и больше ничего на карточке его не тащит.
            Box(
                modifier = Modifier.size(if (compact) 44.dp else 52.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier.fillMaxSize(),
                    color = ringColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeWidth = if (compact) 3.dp else 4.dp,
                    strokeCap = StrokeCap.Round,
                )
                Box(
                    modifier = Modifier
                        .size(if (compact) 34.dp else 42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    IssuerIcon(
                        issuer = account.issuer,
                        monogramSize = if (compact) 12.sp else 18.sp,
                        modifier = Modifier.size(if (compact) 22.dp else 28.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.issuer,
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Секунды подают голос только на последних десяти: всё остальное время
                    // говорит кольцо.
                    if (soon) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "$secondsRemaining с",
                            color = ringColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (compact) 11.sp else 12.sp,
                        )
                    }
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
                // Кегль кода подбирается по фактической ширине места. Моноширинная цифра
                // занимает ~0.6 em, берём 0.64 с запасом, поэтому «382 910» ужимается,
                // но не обрезается. Потолок 28/32 sp, пол 12 sp — читаемо и в двух колонках.
                BoxWithConstraints {
                    val formatted = formatCode(code)
                    val fit = maxWidth.value / (0.64f * formatted.length.coerceAtLeast(1))
                    Text(
                        text = formatted,
                        fontSize = fit
                            .coerceAtMost(if (compact) 28f else 32f)
                            .coerceAtLeast(12f)
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
