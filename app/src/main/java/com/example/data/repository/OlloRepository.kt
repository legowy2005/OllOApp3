package com.example.data.repository

import com.example.data.database.OlloDatabase
import com.example.data.entity.CardEntity
import com.example.data.entity.FolderEntity
import com.example.data.entity.ImageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

data class CardWithFolderName(
    val card: CardEntity,
    val folderName: String
)

class OlloRepository(
    private val database: OlloDatabase
) {
    private val folderDao = database.folderDao()
    private val cardDao = database.cardDao()
    private val imageDao = database.imageDao()

    val allFolders: Flow<List<FolderEntity>> = folderDao.getAllFolders()

    fun getCardsForFolder(folderId: Long): Flow<List<CardEntity>> =
        cardDao.getCardsForFolder(folderId)

    fun searchCardsWithFolder(query: String): Flow<List<CardWithFolderName>> {
        return combine(cardDao.searchCards(query), folderDao.getAllFolders()) { cards, folders ->
            val folderMap = folders.associateBy { it.id }
            cards.map { card ->
                CardWithFolderName(
                    card = card,
                    folderName = folderMap[card.folderId]?.name ?: "Unknown"
                )
            }
        }
    }

    suspend fun getFolderById(id: Long): FolderEntity? = withContext(Dispatchers.IO) {
        folderDao.getFolderById(id)
    }

    suspend fun addFolder(name: String): Long = withContext(Dispatchers.IO) {
        val count = folderDao.getFolderCount()
        folderDao.insertFolder(
            FolderEntity(
                name = name,
                sortOrder = count,
                includeInSync = true
            )
        )
    }

    suspend fun renameFolder(id: Long, newName: String) = withContext(Dispatchers.IO) {
        folderDao.renameFolder(id, newName)
    }

    suspend fun setFolderIncludeInSync(id: Long, include: Boolean) = withContext(Dispatchers.IO) {
        folderDao.setIncludeInSync(id, include)
    }

    suspend fun deleteFolder(id: Long) = withContext(Dispatchers.IO) {
        folderDao.deleteFolderById(id)
        cardDao.deleteCardsByFolderId(id)
        imageDao.deleteUnreferencedImages()
    }

    suspend fun reorderFolders(orderedFolderIds: List<Long>) = withContext(Dispatchers.IO) {
        orderedFolderIds.forEachIndexed { index, folderId ->
            folderDao.updateSortOrder(folderId, index)
        }
    }

    suspend fun addCard(
        folderId: Long,
        frontText: String,
        backText: String,
        frontImageId: Long? = null,
        backImageId: Long? = null
    ): Long = withContext(Dispatchers.IO) {
        val count = cardDao.getCardCountForFolder(folderId)
        val id = cardDao.insertCard(
            CardEntity(
                folderId = folderId,
                sortOrder = count,
                frontText = frontText,
                backText = backText,
                frontImageId = frontImageId,
                backImageId = backImageId
            )
        )
        // Update folder updated_at timestamp
        val folder = folderDao.getFolderById(folderId)
        if (folder != null) {
            folderDao.updateFolder(folder.copy(updatedAt = System.currentTimeMillis()))
        }
        id
    }

    suspend fun updateCard(
        cardId: Long,
        frontText: String,
        backText: String,
        frontImageId: Long? = null,
        backImageId: Long? = null
    ) = withContext(Dispatchers.IO) {
        val existing = cardDao.getCardById(cardId) ?: return@withContext
        cardDao.updateCard(
            existing.copy(
                frontText = frontText,
                backText = backText,
                frontImageId = frontImageId,
                backImageId = backImageId,
                updatedAt = System.currentTimeMillis()
            )
        )
        imageDao.deleteUnreferencedImages()
    }

    suspend fun deleteCard(cardId: Long) = withContext(Dispatchers.IO) {
        cardDao.deleteCardById(cardId)
        imageDao.deleteUnreferencedImages()
    }

    suspend fun duplicateCard(cardId: Long): Long? = withContext(Dispatchers.IO) {
        val existing = cardDao.getCardById(cardId) ?: return@withContext null
        val count = cardDao.getCardCountForFolder(existing.folderId)
        cardDao.insertCard(
            existing.copy(
                id = 0L,
                sortOrder = count,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun moveCard(cardId: Long, targetFolderId: Long) = withContext(Dispatchers.IO) {
        val targetCount = cardDao.getCardCountForFolder(targetFolderId)
        cardDao.moveCard(cardId, targetFolderId, targetCount)
    }

    suspend fun reorderCards(orderedCardIds: List<Long>) = withContext(Dispatchers.IO) {
        orderedCardIds.forEachIndexed { index, cardId ->
            cardDao.updateSortOrder(cardId, index)
        }
    }

    suspend fun saveImage(image: ImageEntity) = withContext(Dispatchers.IO) {
        imageDao.insertImage(image)
    }

    suspend fun getImage(id: Long): ImageEntity? = withContext(Dispatchers.IO) {
        imageDao.getImageById(id)
    }

    suspend fun getCardsToSync(): List<CardEntity> = withContext(Dispatchers.IO) {
        cardDao.getCardsToSync()
    }

    suspend fun getImagesForSync(imageIds: List<Long>): List<ImageEntity> = withContext(Dispatchers.IO) {
        if (imageIds.isEmpty()) emptyList()
        else imageDao.getImagesByIds(imageIds)
    }

    /**
     * Export folder's cards as CSV format: front,back
     */
    suspend fun exportFolderCsv(folderId: Long): String = withContext(Dispatchers.IO) {
        val stringBuilder = StringBuilder()
        val folder = folderDao.getFolderById(folderId)
        // Header
        stringBuilder.append("front,back\n")
        val cards = cardDao.getCardsToSync().filter { it.folderId == folderId }
        for (card in cards) {
            val escapedFront = escapeCsvField(card.frontText)
            val escapedBack = escapeCsvField(card.backText)
            stringBuilder.append("$escapedFront,$escapedBack\n")
        }
        stringBuilder.toString()
    }

    /**
     * Import CSV into destination folder (or create new folder named after the file).
     */
    suspend fun importCsv(folderName: String, csvContent: String, existingFolderId: Long? = null): Long = withContext(Dispatchers.IO) {
        val targetFolderId = existingFolderId ?: addFolder(folderName)
        val lines = csvContent.lines()
        var isFirst = true
        var currentSortOrder = cardDao.getCardCountForFolder(targetFolderId)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Skip header if present
            if (isFirst && (trimmed.equals("front,back", ignoreCase = true) || trimmed.startsWith("front,"))) {
                isFirst = false
                continue
            }
            isFirst = false

            val parts = parseCsvLine(trimmed)
            if (parts.isNotEmpty()) {
                val front = parts.getOrNull(0) ?: ""
                val back = parts.getOrNull(1) ?: ""
                cardDao.insertCard(
                    CardEntity(
                        folderId = targetFolderId,
                        sortOrder = currentSortOrder++,
                        frontText = front,
                        backText = back
                    )
                )
            }
        }
        targetFolderId
    }

    /**
     * Section 8 Offline estimate:
     * per card about 20 + frontBytes + backBytes bytes;
     * per unique image 4 + dataLen, rounded up to the next 4,096 bytes (small-file filesystem blocks).
     * Count only cards that would be synced.
     */
    suspend fun calculateOfflineEstimate(): Long = withContext(Dispatchers.IO) {
        val cardsToSync = cardDao.getCardsToSync()
        var totalBytes = 0L

        val uniqueImageIds = mutableSetOf<Long>()
        for (card in cardsToSync) {
            val fBytes = card.frontText.toByteArray(Charsets.US_ASCII).size
            val bBytes = card.backText.toByteArray(Charsets.US_ASCII).size
            totalBytes += (20 + fBytes + bBytes)

            card.frontImageId?.let { if (it > 0) uniqueImageIds.add(it) }
            card.backImageId?.let { if (it > 0) uniqueImageIds.add(it) }
        }

        if (uniqueImageIds.isNotEmpty()) {
            val images = imageDao.getImagesByIds(uniqueImageIds.toList())
            for (img in images) {
                val rawSize = 4 + img.deviceData.size
                val blockRounded = ((rawSize + 4095) / 4096) * 4096L
                totalBytes += blockRounded
            }
        }

        totalBytes
    }

    private fun escapeCsvField(field: String): String {
        return if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var inQuotes = false
        val currentField = StringBuilder()
        var i = 0

        while (i < line.length) {
            val c = line[i]
            if (c == '\"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '\"') {
                    currentField.append('\"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                result.add(currentField.toString().trim())
                currentField.setLength(0)
            } else {
                currentField.append(c)
            }
            i++
        }
        result.add(currentField.toString().trim())
        return result
    }
}
