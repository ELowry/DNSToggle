package com.ericlowry.dnstoggle.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SecurityRepository {
	private val _isInitialized = MutableStateFlow(false)
	val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

	private val _isKeyInvalidated = MutableStateFlow(false)
	val isKeyInvalidated: StateFlow<Boolean> = _isKeyInvalidated.asStateFlow()

	fun initialize() {
		_isInitialized.value = true
	}

	fun setKeyInvalidated(invalidated: Boolean) {
		_isKeyInvalidated.value = invalidated
	}

	fun resetKeyInvalidated() {
		_isKeyInvalidated.value = false
	}
}
