package com.plcoding.backgroundlocationtracking.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_tracking")
data class OfflineTrackingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val oid: String,
    val deviceID: String,
    val title: String?,
    val latitude: Double,
    val longitude: Double,
    val recordDate: String,
    val optimisticLockField: Int?,
    val gcRecord: Int?,
    val userName: String?
)
