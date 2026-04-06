package com.example.spinotron

import androidx.compose.runtime.mutableStateOf

object SensorState {
    var totalAngle: Double
        get() = _totalAngle.value
        set(v) { _totalAngle.value = v }
    val _totalAngle = mutableStateOf(0.0)

    var startTimeMs = mutableStateOf(0L)
    var isRunning = mutableStateOf(false)
}