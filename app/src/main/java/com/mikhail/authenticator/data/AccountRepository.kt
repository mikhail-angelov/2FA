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
     * Поднимает аккаунт в начало списка: ставит ему порядок меньше наименьшего в базе.
     *
     * Отдельной колонки-приоритета не заводим: список и так сортируется по `sortOrder`, а
     * место в нём — это и есть приоритет. Порядок занимают по мере добавления (0, 1, 2…),
     * поэтому «первым» = «меньше всех», и повторное поднятие разных аккаунтов выстраивает их
     * сверху вниз в порядке поднятия.
     */
    suspend fun moveToTop(entry: StoredAccount) {
        val lowest = dao.minSortOrder() ?: 0
        dao.setSortOrder(entry.id, lowest - 1)
    }

    /**
     * Adds accounts from a vault payload.
     * @param skipDuplicates when true, accounts already present (by secret) are ignored.
     * @return how many accounts were actually added.
     */
    suspend fun import(entries: List<VaultEntry>, skipDuplicates: Boolean = true): Int =
        importAccounts(entries.map { it.toAccount() }, skipDuplicates).added

    /** How an import went: how many landed, how many were already there. */
    data class ImportOutcome(val added: Int, val duplicates: Int)

    /**
     * Adds accounts, skipping any whose Base32 secret is already stored.
     *
     * Duplicates are matched by the secret itself, not by the visible label: the same account
     * re-imported (from a backup or from a Google Authenticator screenshot) usually arrives
     * with a slightly different name, while the secret is byte-identical. Rows are encrypted
     * with a random IV each, so ciphertexts cannot be compared in SQL — the comparison happens
     * here, on values that are already decrypted for display anyway.
     */
    suspend fun importAccounts(accounts: List<OtpAccount>, skipDuplicates: Boolean = true): ImportOutcome {
        val seen = if (skipDuplicates) this.accounts().mapTo(mutableSetOf()) { it.secret } else mutableSetOf()
        var added = 0
        var duplicates = 0
        for (account in accounts) {
            if (!seen.add(account.secret)) {
                duplicates++
                continue
            }
            add(account)
            added++
        }
        return ImportOutcome(added, duplicates)
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
