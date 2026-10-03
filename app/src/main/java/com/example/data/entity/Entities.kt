package com.example.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "include_in_sync", defaultValue = "1")
    val includeInSync: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("folder_id"),
        Index("sort_order")
    ]
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "folder_id")
    val folderId: Long,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "front_text")
    val frontText: String = "",
    @ColumnInfo(name = "back_text")
    val backText: String = "",
    @ColumnInfo(name = "front_image_id")
    val frontImageId: Long? = null,
    @ColumnInfo(name = "back_image_id")
    val backImageId: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey
    val id: Long, // 32-bit CRC32 id stored as Long
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "device_data", typeAffinity = ColumnInfo.BLOB)
    val deviceData: ByteArray,
    @ColumnInfo(name = "display_data", typeAffinity = ColumnInfo.BLOB)
    val displayData: ByteArray? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    /** 0 = 1-bit packed, 1 = RGB565 little-endian (experimental color) */
    @ColumnInfo(name = "format", defaultValue = "0")
    val format: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImageEntity
        if (id != other.id) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (format != other.format) return false
        if (!deviceData.contentEquals(other.deviceData)) return false
        if (displayData != null) {
            if (other.displayData == null) return false
            if (!displayData.contentEquals(other.displayData)) return false
        } else if (other.displayData != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + deviceData.contentHashCode()
        result = 31 * result + (displayData?.contentHashCode() ?: 0)
        return result
    }
}
