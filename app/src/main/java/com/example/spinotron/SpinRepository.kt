package com.example.spinotron

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Fenêtre d'agrégation de l'historique, en millisecondes. */
const val HISTORY_INTERVAL_MS = 10_000L

data class SpinUiState(
    val totalAngleRad: Double = 0.0,
    val currentAngleRad: Double = 0.0,
    val currentDeltaRad: Double = 0.0,
    val isRunning: Boolean = false,
    val elapsedMs: Long = 0L,
    val turnsHistory: List<Double> = emptyList(),
)

/**
 * Source de vérité unique, partagée entre le Service (producteur) et l'UI (consommateur).
 * Le Service et l'Activity tournent dans le même process, donc un objet in-memory suffit —
 * pas besoin de Binder/AIDL.
 */
object SpinRepository {
    private val _state = MutableStateFlow(SpinUiState())
    val state: StateFlow<SpinUiState> = _state.asStateFlow()

    fun setRunning(running: Boolean) {
        _state.update { it.copy(isRunning = running) }
    }

    fun addRotation(deltaRad: Double, currentAngleRad: Double) {
        _state.update {
            it.copy(
                totalAngleRad = it.totalAngleRad + deltaRad,
                currentDeltaRad = deltaRad,
                currentAngleRad = currentAngleRad,
            )
        }
    }

    fun setCurrentAngle(currentAngleRad: Double) {
        _state.update { it.copy(currentAngleRad = currentAngleRad) }
    }

    fun tick(elapsedMs: Long) {
        _state.update { it.copy(elapsedMs = elapsedMs) }
    }

    fun pushHistoryPoint(turnsInWindow: Double) {
        _state.update { it.copy(turnsHistory = it.turnsHistory + turnsInWindow) }
    }

    fun reset() {
        _state.value = SpinUiState()
    }
}
