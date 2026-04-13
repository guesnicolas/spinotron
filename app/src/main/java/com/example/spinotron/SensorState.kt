package com.example.spinotron

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

object SensorState {
    val totalAngle = mutableStateOf(0.0)
    val currentAngle = mutableStateOf(0.0)
    val currentDtheta = mutableStateOf(0.0)
    val startTimeMs = mutableStateOf(0L)
    val isRunning = mutableStateOf(false)
    val elapsedMs = mutableStateOf(0L)
    val turnsHistory = mutableStateListOf<Double>()
}