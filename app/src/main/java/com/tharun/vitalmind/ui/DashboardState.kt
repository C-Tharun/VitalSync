package com.tharun.vitalmind.ui

data class DashboardState(
    val userName: String = "",
    val heartRate: String = "--",
    val calories: String = "--",
    val steps: String = "--",
    val distance: String = "--",
    val heartPoints: String = "--",
    val sleepDuration: String = "--",
    val lastActivity: String = "None",
    val lastActivityTime: String = "",
    val weeklySteps: List<Pair<String, Float>> = emptyList(),
    val weeklyCalories: List<Pair<String, Float>> = emptyList(),
    val weeklyHeartPoints: List<Pair<String, Float>> = emptyList(),
    val weight: String = "--",
    val floorsClimbed: String = "--",
    val moveMinutes: String = "--"
)
