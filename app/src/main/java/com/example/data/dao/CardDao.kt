package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.CardEntity
import kotlinx.coroutines.flow.Flow

data class FolderCardCount(
    @androidx.room.ColumnInfo(name = "folder_id") val folderId: Long,
    @androidx.room.ColumnInfo(name = "card_count") val cardCount: Int
)

@Dao
interface CardDao {
    @Query("SELECT folder_id AS folder_id, COUNT(*) AS card_count FROM cards GROUP BY folder_id")
    fun getCardCountsPerFolder(): Flow<List<FolderCardCount>>

    @Query("SELECT * FROM cards WHERE folder_id = :folderId ORDER BY sort_order ASC, created_at ASC")
    fun getCardsForFolder(folderId: Long): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards ORDER BY sort_order ASC")
    fun getAllCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE id = :id LIMIT 1")
    suspend fun getCardById(id: Long): CardEntity?

    @Query("SELECT COUNT(*) FROM cards WHERE folder_id = :folderId")
    suspend fun getCardCountForFolder(folderId: Long): Int

    @Query("SELECT * FROM cards WHERE front_text LIKE '%' || :query || '%' OR back_text LIKE '%' || :query || '%' ORDER BY updated_at DESC")
    fun searchCards(query: String): Flow<List<CardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: CardEntity): Long

    @Update
    suspend fun updateCard(card: CardEntity)

    @Query("UPDATE cards SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE cards SET folder_id = :targetFolderId, sort_order = :newSortOrder, updated_at = :updatedAt WHERE id = :cardId")
    suspend fun moveCard(cardId: Long, targetFolderId: Long, newSortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteCardById(id: Long)

    @Query("DELETE FROM cards WHERE folder_id = :folderId")
    suspend fun deleteCardsByFolderId(folderId: Long)

    /**
     * Query for cards to sync:
     * Folders with include_in_sync = 1, ordered by folder sort_order, then card sort_order.
     */
    @Query("""
        SELECT c.* FROM cards c
        INNER JOIN folders f ON c.folder_id = f.id
        WHERE f.include_in_sync = 1
        ORDER BY f.sort_order ASC, f.id ASC, c.sort_order ASC, c.id ASC
    """)
    suspend fun getCardsToSync(): List<CardEntity>
}
