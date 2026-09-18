package com.example.spinotron

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Nombre de points conservés dans le graphique avant qu'il ne se recompresse. */
private const val MAX_TREND_POINTS = 200
private const val INITIAL_SAMPLE_INTERVAL_MS = 250L

data class TrendPoint(val elapsedMs: Long, val totalTurns: Double)

data class SpinUiState(
    val totalAngleRad: Double = 0.0,
    val currentAngleRad: Double = 0.0,
    val currentDeltaRad: Double = 0.0,
    val isRunning: Boolean = false,
    val elapsedMs: Long = 0L,
    val trend: List<TrendPoint> = emptyList(),
    val sensorUnavailable: Boolean = false,
)

/**
 * Source de vérité unique, partagée entre le Service (producteur) et l'UI (consommateur).
 * Le Service et l'Activity tournent dans le même process, donc un objet in-memory suffit —
 * pas besoin de Binder/AIDL.
 */
object SpinRepository {
    private val _state = MutableStateFlow(SpinUiState())
    val state: StateFlow<SpinUiState> = _state.asStateFlow()

    // Intervalle d'échantillonnage courant du graphique : il double à chaque fois que le
    // nombre de points dépasse MAX_TREND_POINTS, pour que le graphe couvre toute la durée
    // de l'enregistrement avec un nombre de points borné (il "se compresse" avec le temps).
    private var sampleIntervalMs = INITIAL_SAMPLE_INTERVAL_MS
    private var lastSampledAtMs = -1L

    fun setRunning(running: Boolean) {
        _state.update { it.copy(isRunning = running) }
    }

    fun setSensorUnavailable() {
        _state.update { it.copy(isRunning = false, sensorUnavailable = true) }
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
        sampleTrend(elapsedMs)
    }

    private fun sampleTrend(elapsedMs: Long) {
        if (lastSampledAtMs >= 0 && elapsedMs - lastSampledAtMs < sampleIntervalMs) return
        lastSampledAtMs = elapsedMs

        _state.update {
            var points = it.trend + TrendPoint(elapsedMs, it.totalAngleRad / (2 * Math.PI))
            if (points.size > MAX_TREND_POINTS) {
                points = points.filterIndexed { index, _ -> index % 2 == 0 }
                sampleIntervalMs *= 2
            }
            it.copy(trend = points)
        }
    }

    fun reset() {
        sampleIntervalMs = INITIAL_SAMPLE_INTERVAL_MS
        lastSampledAtMs = -1L
        _state.value = SpinUiState()
    }
}
