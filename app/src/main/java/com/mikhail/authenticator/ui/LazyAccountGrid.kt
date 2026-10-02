package com.mikhail.authenticator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.mikhail.authenticator.crypto.Totp
import com.mikhail.authenticator.data.StoredAccount

@Composable
fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * Adaptive grid (spec §2.2): one column in portrait, two in landscape / on tablets and
 * foldables. Each card shows its own live code, computed from the shared clock.
 */
@Composable
fun LazyAccountGrid(
    accounts: List<StoredAccount>,
    columns: Int,
    nowSeconds: Long,
    onCopy: (String) -> Unit,
    onLongPress: (StoredAccount) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(accounts, key = { it.id }) { account ->
            val code = runCatching {
                Totp.generate(account.secret, nowSeconds, account.period, account.digits, account.algorithm)
            }.getOrElse { "______" }
            val remaining = Totp.secondsRemaining(nowSeconds, account.period)

            OtpCard(
                account = account,
                code = code,
                secondsRemaining = remaining,
                modifier = Modifier.padding(0.dp),
                compact = columns > 1,
                onCopy = { onCopy(code) },
                onLongPress = { onLongPress(account) },
            )
        }
    }
}
