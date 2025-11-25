package com.aiguru.android_file_encryption.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aiguru.android_file_encryption.ui.screens.VaultInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _vaults = MutableStateFlow<List<VaultInfo>>(emptyList())
    val vaults: StateFlow<List<VaultInfo>> = _vaults.asStateFlow()

    init {
        // TODO: Load vaults from encrypted storage
        // For now, start with empty list
    }

    fun addVault(vault: VaultInfo) {
        _vaults.value = _vaults.value + vault
        // TODO: Persist to encrypted storage
    }

    fun removeVault(vaultId: String) {
        _vaults.value = _vaults.value.filter { it.id != vaultId }
        // TODO: Remove from encrypted storage
    }

    fun updateVaultStatus(vaultId: String, isLocked: Boolean) {
        _vaults.value = _vaults.value.map { vault ->
            if (vault.id == vaultId) vault.copy(isLocked = isLocked) else vault
        }
    }
}