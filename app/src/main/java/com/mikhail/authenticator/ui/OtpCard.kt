package com.mikhail.authenticator.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mikhail.authenticator.data.StoredAccount
import com.mikhail.authenticator.icons.IssuerIcons

/**
 * One account card: issuer icon, label, the current code, and the 30-second ring.
 * Tapping anywhere copies the code (spec §2.3).
 */
@Composable
fun OtpCard(
    account: StoredAccount,
    code: String,
    secondsRemaining: Int,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val fraction = (secondsRemaining.toFloat() / account.period.toFloat()).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(targetValue = fraction, label = "period-progress")
    // The ring turns amber in the last five seconds: a quiet nudge that the code is about to change.
    val ringColor = if (secondsRemaining <= 5) Color(0xFFE6A700) else MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IssuerIcon(account.issuer, modifier = Modifier.size(44.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = account.issuer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.account,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatCode(code),
                    fontSize = 30.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }

            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier.size(42.dp),
                    color = ringColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 4.dp,
                )
                Text(
                    text = secondsRemaining.toString(),
                    style = MaterialTheme.typography.labelMedium,
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

@Composable
private fun IssuerIcon(issuer: String, modifier: Modifier = Modifier) {
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
                fontSize = 20.sp,
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
