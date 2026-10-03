package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {
    @Query("SELECT * FROM images WHERE id = :id LIMIT 1")
    suspend fun getImageById(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE id = :id LIMIT 1")
    fun getImageByIdFlow(id: Long): Flow<ImageEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImage(image: ImageEntity)

    @Query("""
        DELETE FROM images 
        WHERE id NOT IN (SELECT front_image_id FROM cards WHERE front_image_id IS NOT NULL)
          AND id NOT IN (SELECT back_image_id FROM cards WHERE back_image_id IS NOT NULL)
    """)
    suspend fun deleteUnreferencedImages(): Int

    @Query("SELECT * FROM images WHERE id IN (:ids)")
    suspend fun getImagesByIds(ids: List<Long>): List<ImageEntity>
}
