package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY sort_order ASC, created_at ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY sort_order ASC, created_at ASC")
    suspend fun getAllFoldersOnce(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: Long): FolderEntity?

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun getFolderCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity): Long

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE folders SET include_in_sync = :include, updated_at = :updatedAt WHERE id = :id")
    suspend fun setIncludeInSync(id: Long, include: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE folders SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderById(id: Long)
}
