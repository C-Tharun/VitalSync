package com.tharun.vitalmind.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(healthData: HealthData)

    @Query("SELECT * FROM health_data WHERE userId = :userId AND timestamp = :timestamp LIMIT 1")
    suspend fun getHealthDataByTimestamp(userId: String, timestamp: Long): HealthData?

    @Query("SELECT * FROM health_data WHERE userId = :userId ORDER BY timestamp DESC")
    fun getAllHealthData(userId: String): Flow<List<HealthData>>

    @Query("SELECT * FROM health_data WHERE userId = :userId AND timestamp >= :startTime ORDER BY timestamp DESC")
    fun getHealthDataSince(userId: String, startTime: Long): Flow<List<HealthData>>

    // New queries for history screen
    @Query("SELECT * FROM health_data WHERE userId = :userId AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    fun getDataForRange(userId: String, startTime: Long, endTime: Long): Flow<List<HealthData>>

    @Query("SELECT * FROM health_data WHERE userId = :userId AND activityType IS NOT NULL AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    fun getActivityHistoryForRange(userId: String, startTime: Long, endTime: Long): Flow<List<HealthData>>
}
