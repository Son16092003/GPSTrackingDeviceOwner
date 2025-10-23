package com.plcoding.backgroundlocationtracking.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OfflineTrackingDao {

    // --- Thêm bản ghi offline ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tracking: OfflineTrackingEntity)

    // --- Lấy tất cả bản ghi pending (ưu tiên sắp xếp theo id tăng dần để gửi đúng thứ tự lưu) ---
    @Query("SELECT * FROM offline_tracking ORDER BY id ASC")
    suspend fun getAll(): List<OfflineTrackingEntity>

    // --- Xóa bản ghi sau khi gửi thành công ---
    @Query("DELETE FROM offline_tracking WHERE id = :id")
    suspend fun deleteById(id: Int)

    // --- Xóa toàn bộ bản ghi (khi cần reset DB) ---
    @Query("DELETE FROM offline_tracking")
    suspend fun deleteAll()
}
