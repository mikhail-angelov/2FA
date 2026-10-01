package com.mikhail.authenticator.data

import com.mikhail.authenticator.crypto.OtpAlgorithm
import com.mikhail.authenticator.otp.OtpAccount
import com.mikhail.authenticator.vault.VaultEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only place that knows secrets are stored encrypted. Everything above it works with
 * plaintext [StoredAccount] values held in memory for the lifetime of the screen.
 */
class AccountRepository(
    private val dao: OtpDao,
    private val cipher: SecretCipher = SecretCipher(),
) {

    fun observeAccounts(): Flow<List<StoredAccount>> =
        dao.observeAll().map { entries -> entries.mapNotNull(::decrypt) }

    suspend fun accounts(): List<StoredAccount> = dao.all().mapNotNull(::decrypt)

    suspend fun add(account: OtpAccount): Long {
        val nextOrder = dao.count()
        return dao.insert(
            OtpEntry(
                issuer = account.issuer,
                account = account.account,
                secretEncrypted = cipher.encrypt(account.secret),
                algorithm = account.algorithm.otpauthName,
                digits = account.digits,
                period = account.period,
                sortOrder = nextOrder,
            ),
        )
    }

    suspend fun delete(entry: StoredAccount) {
        dao.byId(entry.id)?.let { dao.delete(it) }
    }

    /**
     * Adds accounts from a vault payload.
     * @param skipDuplicates when true, entries with the same issuer+account are ignored
     *   (the default on import — re-importing a backup should not double every entry).
     * @return how many accounts were actually added.
     */
    suspend fun import(entries: List<VaultEntry>, skipDuplicates: Boolean = true): Int {
        var added = 0
        for (entry in entries) {
            if (skipDuplicates && dao.countByLabel(entry.issuer, entry.account) > 0) continue
            add(entry.toAccount())
            added++
        }
        return added
    }

    suspend fun exportEntries(): List<VaultEntry> = accounts().map { stored ->
        VaultEntry.from(
            OtpAccount(
                issuer = stored.issuer,
                account = stored.account,
                secret = stored.secret,
                algorithm = stored.algorithm,
                digits = stored.digits,
                period = stored.period,
            ),
        )
    }

    private fun decrypt(entry: OtpEntry): StoredAccount? = runCatching {
        StoredAccount(
            id = entry.id,
            issuer = entry.issuer,
            account = entry.account,
            secret = cipher.decrypt(entry.secretEncrypted),
            algorithm = OtpAlgorithm.fromOtpAuth(entry.algorithm),
            digits = entry.digits,
            period = entry.period,
        )
    }.getOrNull() // An unreadable row (key reset, corrupt blob) must not crash the list.
}
