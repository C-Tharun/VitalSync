package com.tharun.vitalsync.data

import androidx.room.Entity

@Entity(tableName = "health_data", primaryKeys = ["userId", "timestamp"])
data class HealthData(
    val userId: String, // To store data per Google account
    val timestamp: Long,
    val heartRate: Float? = null,
    val steps: Int? = null,
    val calories: Float? = null,
    val heartPoints: Int? = null,
    val distance: Float? = null, // in kilometers
    val activityType: String? = null,
    val sleepDuration: Long? = null // in minutes
)
