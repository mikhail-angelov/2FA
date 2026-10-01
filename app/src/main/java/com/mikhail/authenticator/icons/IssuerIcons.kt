package com.mikhail.authenticator.icons

import androidx.compose.ui.graphics.Color

/**
 * Issuer → icon resolution, in three steps (spec §3.Б):
 *
 *  1. A built-in table of popular issuers: domain (for the favicon fallback) and brand
 *     colour for the locally drawn monogram.
 *  2. Coil fetches the favicon for a known domain when the network is available.
 *  3. Whatever happens (offline, unknown issuer, fetch failure) the card shows a coloured
 *     circle with the issuer's first letter — so the list is never iconless.
 *
 * Deliberately no bundled brand vector assets: drawing ~40 brand marks by hand is a lot of
 * surface for little gain, and shipping third-party logos has its own licensing question.
 */
object IssuerIcons {

    private data class Brand(val domain: String, val color: Long)

    /** Lower-case issuer (or its first word) → domain + brand colour. */
    private val brands: Map<String, Brand> = mapOf(
        "google" to Brand("google.com", 0xFF4285F4),
        "github" to Brand("github.com", 0xFF24292F),
        "gitlab" to Brand("gitlab.com", 0xFFFC6D26),
        "bitbucket" to Brand("bitbucket.org", 0xFF0052CC),
        "telegram" to Brand("telegram.org", 0xFF229ED9),
        "discord" to Brand("discord.com", 0xFF5865F2),
        "slack" to Brand("slack.com", 0xFF4A154B),
        "microsoft" to Brand("microsoft.com", 0xFF00A4EF),
        "outlook" to Brand("outlook.com", 0xFF0078D4),
        "apple" to Brand("apple.com", 0xFF555555),
        "amazon" to Brand("amazon.com", 0xFFFF9900),
        "aws" to Brand("aws.amazon.com", 0xFFFF9900),
        "facebook" to Brand("facebook.com", 0xFF1877F2),
        "meta" to Brand("meta.com", 0xFF0866FF),
        "instagram" to Brand("instagram.com", 0xFFE4405F),
        "twitter" to Brand("twitter.com", 0xFF1DA1F2),
        "x" to Brand("x.com", 0xFF000000),
        "linkedin" to Brand("linkedin.com", 0xFF0A66C2),
        "reddit" to Brand("reddit.com", 0xFFFF4500),
        "dropbox" to Brand("dropbox.com", 0xFF0061FF),
        "binance" to Brand("binance.com", 0xFFF0B90B),
        "coinbase" to Brand("coinbase.com", 0xFF0052FF),
        "kraken" to Brand("kraken.com", 0xFF5741D9),
        "yandex" to Brand("yandex.ru", 0xFFFC3F1D),
        "mail.ru" to Brand("mail.ru", 0xFF005FF9),
        "vk" to Brand("vk.com", 0xFF0077FF),
        "sber" to Brand("sberbank.ru", 0xFF21A038),
        "tinkoff" to Brand("tinkoff.ru", 0xFFFFDD2D),
        "steam" to Brand("steampowered.com", 0xFF171A21),
        "epic" to Brand("epicgames.com", 0xFF2A2A2A),
        "cloudflare" to Brand("cloudflare.com", 0xFFF38020),
        "digitalocean" to Brand("digitalocean.com", 0xFF0080FF),
        "heroku" to Brand("heroku.com", 0xFF430098),
        "npm" to Brand("npmjs.com", 0xFFCB3837),
        "docker" to Brand("docker.com", 0xFF2496ED),
        "openai" to Brand("openai.com", 0xFF10A37F),
        "anthropic" to Brand("anthropic.com", 0xFFD97757),
        "proton" to Brand("proton.me", 0xFF6D4AFF),
        "fastmail" to Brand("fastmail.com", 0xFF1F6FEB),
        "notion" to Brand("notion.so", 0xFF000000),
        "figma" to Brand("figma.com", 0xFFF24E1E),
        "zoom" to Brand("zoom.us", 0xFF2D8CFF),
        "twitch" to Brand("twitch.tv", 0xFF9146FF),
        "patreon" to Brand("patreon.com", 0xFFFF424D),
        "youtube" to Brand("youtube.com", 0xFFFF0000),
    )

    private fun lookup(issuer: String): Brand? {
        val key = issuer.trim().lowercase()
        brands[key]?.let { return it }
        // "Amazon Web Services" → try each word before giving up.
        for (word in key.split(' ', '-', '_', '.')) {
            if (word.length < 2) continue
            brands[word]?.let { return it }
        }
        return null
    }

    /** Favicon URL for the issuer, or null when we do not know where it lives. */
    fun faviconUrl(issuer: String): String? =
        lookup(issuer)?.let { "https://icon.horse/icon/${it.domain}" }

    /** Colour for the monogram fallback: brand colour when known, otherwise hash-stable. */
    fun monogramColor(issuer: String): Color {
        lookup(issuer)?.let { return Color(it.color) }
        val palette = listOf(
            0xFF6C5CE7, 0xFF00B894, 0xFFE17055, 0xFF0984E3, 0xFFD63031,
            0xFF00CEC9, 0xFFFDCB6E, 0xFFE84393, 0xFF636E72, 0xFFB33939,
        )
        val index = (issuer.lowercase().hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % palette.size
        return Color(palette[index])
    }

    /** First meaningful letter of the issuer — the label inside the fallback circle. */
    fun monogram(issuer: String): String =
        issuer.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}
