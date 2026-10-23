package com.ridervoice.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppEvents @Inject constructor() {
    private val _updateRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updateRequired: SharedFlow<Unit> = _updateRequired.asSharedFlow()

    fun emitUpdateRequired() {
        _updateRequired.tryEmit(Unit)
    }

    private val _duplicateIdentity = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val duplicateIdentity: SharedFlow<String> = _duplicateIdentity.asSharedFlow()

    fun emitDuplicateIdentity(reason: String) {
        _duplicateIdentity.tryEmit(reason)
    }
}
