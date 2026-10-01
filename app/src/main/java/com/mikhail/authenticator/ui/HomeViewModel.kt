package com.mikhail.authenticator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikhail.authenticator.data.AccountRepository
import com.mikhail.authenticator.data.AppDatabase
import com.mikhail.authenticator.data.StoredAccount
import com.mikhail.authenticator.otp.OtpAccount
import com.mikhail.authenticator.vault.VaultCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AccountRepository(AppDatabase.get(application).otpDao())

    val searchQuery = MutableStateFlow("")

    private val allAccounts: StateFlow<List<StoredAccount>> =
        repository.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Accounts after the search box: matches issuer, account or the group each belongs to. */
    val accounts: StateFlow<List<StoredAccount>> =
        combine(allAccounts, searchQuery) { list, query ->
            val q = query.trim()
            if (q.isEmpty()) list
            else list.filter {
                it.issuer.contains(q, ignoreCase = true) || it.account.contains(q, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(account: OtpAccount) {
        viewModelScope.launch { repository.add(account) }
    }

    fun delete(entry: StoredAccount) {
        viewModelScope.launch { repository.delete(entry) }
    }

    /** Encrypts every account into the export file text (spec §3.В). */
    suspend fun export(password: CharArray): String =
        VaultCodec.encode(repository.exportEntries(), password)

    /** Decrypts an export file and merges it in. Returns how many accounts were added. */
    suspend fun import(fileText: String, password: CharArray): Int =
        repository.import(VaultCodec.decode(fileText, password))
}
