package co.yehaw.browser.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Serialized state of a tab: URL, title, scroll, plus WebView's own navigation back-stack blob. */
@Entity(tableName = "tab_state")
data class TabStateEntity(
    @PrimaryKey val id: String,
    val containerId: String,
    val kind: Int,
    val url: String,
    val title: String,
    val scrollY: Int,
    val position: Int,
    val lastActiveAt: Long,
    val isActive: Boolean,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val webState: ByteArray?,
    val sdkInt: Int,
    val webViewVersion: String?,
)

/** An isolated browsing identity (own cookies / DOM storage / cache) backed by a WebView Profile. */
@Entity(tableName = "containers")
data class ContainerEntity(
    @PrimaryKey val id: String,
    val label: String,
    val createdAt: Long,
)

@Entity(tableName = "archives")
data class ArchiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val filePath: String,
    val sizeBytes: Long,
    val savedAt: Long,
)

object DownloadState {
    const val QUEUED = 0
    const val RUNNING = 1
    const val PAUSED_USER = 2
    const val PAUSED_NETWORK = 3
    const val COMPLETED = 4
    const val FAILED = 5
    const val CANCELED = 6

    fun isActive(s: Int) = s == QUEUED || s == RUNNING
    fun isTerminal(s: Int) = s == COMPLETED || s == FAILED || s == CANCELED
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val userAgent: String?,
    val referer: String?,
    /** Session cookie header for the download; wiped when the download reaches a terminal state. */
    val cookie: String?,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
    val receivedBytes: Long,
    val state: Int,
    val etag: String?,
    val lastModified: String?,
    val createdAt: Long,
    val savedUri: String?,
    val error: String?,
    val retryCount: Int = 0,
)
