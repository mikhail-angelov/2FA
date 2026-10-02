package com.mikhail.authenticator

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mikhail.authenticator.ui.HomeScreen
import com.mikhail.authenticator.ui.TwoFactorTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Spec §3.Г: no screenshots and no thumbnail in the recents list. One-time codes are
        // exactly the kind of secret that must not end up in a screenshot or the task switcher.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        // Экран не гаснет и не затемняется, пока приложение на переднем плане: код нужен в тот
        // момент, когда его вводят, а не после повторной разблокировки. Флаг действует только
        // пока окно видно, поэтому в фоне батарея не тратится.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            TwoFactorTheme {
                HomeScreen()
            }
        }
    }
}
