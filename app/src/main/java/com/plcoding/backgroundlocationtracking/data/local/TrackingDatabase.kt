package com.plcoding.backgroundlocationtracking.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [OfflineTrackingEntity::class], version = 2, exportSchema = false)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun offlineTrackingDao(): OfflineTrackingDao

    companion object {
        @Volatile
        private var INSTANCE: TrackingDatabase? = null

        fun getInstance(context: Context): TrackingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TrackingDatabase::class.java,
                    "tracking_db"
                )
                    .fallbackToDestructiveMigration() // ⚡ Thêm dòng này để tránh lỗi
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
