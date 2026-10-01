package com.mikhail.authenticator.otp

import com.mikhail.authenticator.crypto.OtpAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpAuthUriTest {

    @Test
    fun `parses a full uri`() {
        val parsed = OtpAuthUri.parse(
            "otpauth://totp/GitHub:octocat@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&digits=8&period=60&algorithm=SHA256",
        )!!
        assertEquals("GitHub", parsed.issuer)
        assertEquals("octocat@example.com", parsed.account)
        assertEquals("JBSWY3DPEHPK3PXP", parsed.secret)
        assertEquals(8, parsed.digits)
        assertEquals(60, parsed.period)
        assertEquals(OtpAlgorithm.SHA256, parsed.algorithm)
    }

    @Test
    fun `applies the documented defaults`() {
        val parsed = OtpAuthUri.parse("otpauth://totp/Google:user%40gmail.com?secret=JBSWY3DPEHPK3PXP")!!
        assertEquals("Google", parsed.issuer)
        assertEquals("user@gmail.com", parsed.account)   // percent-decoding
        assertEquals(6, parsed.digits)
        assertEquals(30, parsed.period)
        assertEquals(OtpAlgorithm.SHA1, parsed.algorithm)
    }

    @Test
    fun `issuer parameter wins over the label prefix`() {
        val parsed = OtpAuthUri.parse(
            "otpauth://totp/SomeOldName:me@example.com?secret=JBSWY3DPEHPK3PXP&issuer=RealName",
        )!!
        assertEquals("RealName", parsed.issuer)
        assertEquals("me@example.com", parsed.account)
    }

    @Test
    fun `label without issuer uses its prefix`() {
        val parsed = OtpAuthUri.parse("otpauth://totp/Yandex:user@ya.ru?secret=JBSWY3DPEHPK3PXP")!!
        assertEquals("Yandex", parsed.issuer)
        assertEquals("user@ya.ru", parsed.account)
    }

    @Test
    fun `label without a colon becomes both issuer and account`() {
        val parsed = OtpAuthUri.parse("otpauth://totp/onlyaccount?secret=JBSWY3DPEHPK3PXP")!!
        assertEquals("onlyaccount", parsed.issuer)
        assertEquals("onlyaccount", parsed.account)
    }

    @Test
    fun `normalises the secret the way servers expect`() {
        val parsed = OtpAuthUri.parse("otpauth://totp/Test:a?secret=jbswy3dpehpk3pxp")!!
        assertEquals("JBSWY3DPEHPK3PXP", parsed.secret)
    }

    @Test
    fun `rejects anything that is not a totp code`() {
        assertNull(OtpAuthUri.parse("otpauth://hotp/Test:a?secret=JBSWY3DPEHPK3PXP&counter=1"))
        assertNull(OtpAuthUri.parse("https://example.com/?secret=JBSWY3DPEHPK3PXP"))
        assertNull(OtpAuthUri.parse("otpauth://totp/Test:a"))                       // no secret
        assertNull(OtpAuthUri.parse("just some text"))
        assertNull(OtpAuthUri.parse(""))
    }

    @Test
    fun `out of range parameters fall back to defaults`() {
        val parsed = OtpAuthUri.parse("otpauth://totp/Test:a?secret=JBSWY3DPEHPK3PXP&digits=99&period=0")!!
        assertEquals(6, parsed.digits)
        assertEquals(30, parsed.period)
    }

    @Test
    fun `build and parse round trip`() {
        val account = OtpAccount(
            issuer = "GitHub",
            account = "octocat@example.com",
            secret = "JBSWY3DPEHPK3PXP",
            algorithm = OtpAlgorithm.SHA256,
            digits = 8,
            period = 60,
        )
        val reparsed = OtpAuthUri.parse(OtpAuthUri.build(account))!!
        assertEquals(account.issuer, reparsed.issuer)
        assertEquals(account.account, reparsed.account)
        assertEquals(account.secret, reparsed.secret)
        assertEquals(account.algorithm, reparsed.algorithm)
        assertEquals(account.digits, reparsed.digits)
        assertEquals(account.period, reparsed.period)
    }
}
