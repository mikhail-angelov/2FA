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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mikhail.authenticator.data.StoredAccount
import com.mikhail.authenticator.icons.IssuerIcons

/**
 * One account card (spec §2.3). The countdown ring sits **on the card's top-left rounded
 * corner** — its centre coincides with the centre of the corner arc, so the circle traces the
 * card edge instead of standing beside it — and the issuer icon lives inside the ring, one or
 * two pixels away from it. The text column keeps clear of the ring and carries the name and the
 * current code, and tapping anywhere copies the code.
 *
 * The ring turns amber from ten seconds left and red from five. In the single-column layout the
 * seconds are also printed next to the name, and their slot is reserved at all times so the top
 * line never changes length; in the two-column layout the seconds are dropped entirely — there
 * the ring alone carries the warning and nothing competes with the name.
 *
 * The code is never truncated: its size is fitted to the width actually left on the line **and
 * divided by the system font scale**, because `maxWidth` arrives in dp while text is drawn in
 * sp. Without that division a phone with a larger system font overflows the card and the
 * trailing digits disappear. The monogram fallback is sized from the tile and scaled the same
 * way, so a letter fits inside the icon instead of spilling out of it.
 *
 * [compact] — режим узкой карточки (две колонки в портрете, ландшафт): отступы плотнее, логин
 * и секунды скрыты, кегль кода чуть меньше.
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
    // Кольцо по дуге закругления: диаметр равен двум радиусам угла.
    val corner = if (compact) 14.dp else 18.dp
    val ringSize = corner * 2
    val stroke = if (compact) 2.dp else 2.5.dp
    // Зазор между кольцом и плиткой — 1–2 px, поэтому плитка почти вплотную к окружности.
    val gap = if (compact) 1.dp else 2.dp
    val tileSize = ringSize - stroke * 2 - gap * 2

    val fraction = (secondsRemaining.toFloat() / account.period.toFloat()).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(targetValue = fraction, label = "period-progress")
    val soon = secondsRemaining <= 10
    val ringColor = when {
        secondsRemaining <= 5 -> Color(0xFFD32F2F)
        soon -> Color(0xFFE6A700)
        else -> MaterialTheme.colorScheme.primary
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCopy),
            shape = RoundedCornerShape(corner),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ringSize + if (compact) 6.dp else 8.dp,
                        top = if (compact) 6.dp else 8.dp,
                        end = if (compact) 8.dp else 10.dp,
                        bottom = if (compact) 6.dp else 8.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp),
            ) {
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
                    // В двух колонках секунды убраны совсем: там говорит только кольцо.
                    if (soon && !compact) {
                        Text(
                            text = "$secondsRemaining с",
                            color = ringColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(min = 34.dp),
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

                // Кегль подбирается по фактически оставшейся ширине в dp и делится на системный
                // масштаб шрифта: текст рисуется в sp, а maxWidth приходит в dp — без деления
                // цифры уезжают за край карточки на телефоне с крупным системным шрифтом.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val formatted = formatCode(code)
                    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(0.5f)
                    val fit = maxWidth.value / (0.62f * formatted.length.coerceAtLeast(1)) / fontScale
                    Text(
                        text = formatted,
                        fontSize = fit
                            .coerceAtMost(if (compact) 30f else 36f)
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

        // Кольцо отсчёта сидит ровно на закруглённом углу карточки: центр окружности совпадает
        // с центром дуги закругления, поэтому она обводит край элемента, а не стоит рядом с ним.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(ringSize),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier.fillMaxSize(),
                color = ringColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeWidth = stroke,
                strokeCap = StrokeCap.Round,
            )
            Box(
                modifier = Modifier
                    .size(tileSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                IssuerIcon(
                    issuer = account.issuer,
                    tile = tileSize,
                    modifier = Modifier.size(tileSize * 0.72f),
                )
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

/**
 * Favicon of the issuer when it is known and reachable, otherwise a monogram. Both are sized
 * from [tile] so the fallback letter stays inside the tile whatever the system font scale is.
 */
@Composable
private fun IssuerIcon(issuer: String, tile: Dp, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        val monogram = IssuerIcons.monogram(issuer)
        val chars = monogram.length.coerceAtLeast(1)
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(0.5f)
        val fontSize = (tile.value * 0.52f / chars / fontScale).coerceAtLeast(6f)
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(IssuerIcons.monogramColor(issuer)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = monogram,
                color = Color.White,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }

    val url = IssuerIcons.faviconUrl(issuer)
    if (url == null) {
        fallback()
    } else {
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
