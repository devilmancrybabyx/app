package com.example.albumsharer

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlin.random.Random

/** A single photo or video pulled from MediaStore, with enough metadata to sort in-app. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mimeType: String,
    val isVideo: Boolean,
    val bucketName: String,
    val displayName: String,
    val dateAdded: Long,
    val dateModified: Long
)

private enum class SortMode(val label: String) {
    NEWEST_FIRST("Newest first"),
    OLDEST_FIRST("Oldest first"),
    NAME_AZ("Name (A–Z)"),
    NAME_ZA("Name (Z–A)")
}

class MainActivity : AppCompatActivity() {

    private lateinit var editNumber: TextInputEditText
    private lateinit var imagePreview: ImageView
    private lateinit var playOverlay: View
    private lateinit var statusText: TextView
    private lateinit var btnMenu: ImageButton
    private var pendingAction: (() -> Unit)? = null
    private var sortModeIndex: Int = 0

    private val prefs by lazy { getSharedPreferences("album_sharer_prefs", MODE_PRIVATE) }

    private fun getExcludedFolders(): MutableSet<String> =
        HashSet(prefs.getStringSet("excluded_folders", emptySet()) ?: emptySet())

    private fun saveExcludedFolders(excluded: Set<String>) {
        prefs.edit().putStringSet("excluded_folders", excluded).apply()
    }

    private fun getSavedSortModeIndex(): Int = prefs.getInt("sort_mode", 0)
    private fun saveSortModeIndex(i: Int) = prefs.edit().putInt("sort_mode", i).apply()

    private fun currentSortMode(): SortMode = SortMode.values()[sortModeIndex]

    // Two separate runtime permissions cover all media on Android 13+; one covers everything below that.
    private val mediaPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        else
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.all { it }
            if (granted) {
                pendingAction?.invoke()
            } else {
                statusText.text = "Permission denied — can't access your gallery."
            }
            pendingAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editNumber = findViewById(R.id.editNumber)
        imagePreview = findViewById(R.id.imagePreview)
        playOverlay = findViewById(R.id.playOverlay)
        statusText = findViewById(R.id.statusText)
        btnMenu = findViewById(R.id.btnMenu)
        val btnPreview: MaterialButton = findViewById(R.id.btnPreview)
        val btnShare: MaterialButton = findViewById(R.id.btnShare)
        val btnRandom: MaterialButton = findViewById(R.id.btnRandom)

        sortModeIndex = getSavedSortModeIndex()

        btnPreview.setOnClickListener { withPermission { showPreview() } }
        btnShare.setOnClickListener { withPermission { shareSelectedItem() } }
        btnRandom.setOnClickListener { withPermission { pickRandom() } }
        btnMenu.setOnClickListener { anchor -> showMainMenu(anchor) }
    }

    private fun showMainMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.sort_newest -> { setSortMode(SortMode.NEWEST_FIRST); true }
                R.id.sort_oldest -> { setSortMode(SortMode.OLDEST_FIRST); true }
                R.id.sort_name_az -> { setSortMode(SortMode.NAME_AZ); true }
                R.id.sort_name_za -> { setSortMode(SortMode.NAME_ZA); true }
                R.id.menu_folders -> { withPermission { showFolderPicker() }; true }
                else -> false
            }
        }
        popup.show()
    }

    private fun setSortMode(mode: SortMode) {
        sortModeIndex = SortMode.values().indexOf(mode)
        saveSortModeIndex(sortModeIndex)
        statusText.text = "Sort order: ${mode.label}"
    }

    private fun withPermission(action: () -> Unit) {
        val allGranted = mediaPermissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(mediaPermissions)
        }
    }

    // ---------- MediaStore access: query Images and Video tables separately, then merge ----------
    // (Querying the generic "Files" table is blocked or incomplete on some devices/OEMs;
    // the dedicated Images/Video collections are the reliable way to reach both.)

    private fun queryCollection(collection: Uri, isVideo: Boolean, into: MutableList<MediaItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val bucket = cursor.getString(bucketCol) ?: "Unknown"
                val name = cursor.getString(nameCol) ?: ""
                val added = cursor.getLong(addedCol)
                val modified = cursor.getLong(modCol)
                val mime = cursor.getString(mimeCol) ?: if (isVideo) "video/*" else "image/*"
                val uri = ContentUris.withAppendedId(collection, id)
                into.add(MediaItem(id, uri, mime, isVideo, bucket, name, added, modified))
            }
        }
    }

    /** All photos and videos across the device, folder-exclusion NOT yet applied. */
    private fun loadAllMediaItemsUnfiltered(): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, items)
        queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, items)
        return items
    }

    /** All photos and videos, with excluded folders removed and the chosen sort applied. */
    private fun loadSortedMediaItems(): List<MediaItem> {
        val excluded = getExcludedFolders()
        val filtered = loadAllMediaItemsUnfiltered().filter { it.bucketName !in excluded }

        return when (currentSortMode()) {
            SortMode.NEWEST_FIRST -> filtered.sortedWith(
                compareByDescending<MediaItem> { it.dateAdded }.thenByDescending { it.dateModified }
            )
            SortMode.OLDEST_FIRST -> filtered.sortedWith(
                compareBy<MediaItem> { it.dateAdded }.thenBy { it.dateModified }
            )
            SortMode.NAME_AZ -> filtered.sortedBy { it.displayName.lowercase() }
            SortMode.NAME_ZA -> filtered.sortedByDescending { it.displayName.lowercase() }
        }
    }

    private fun getItemByNumber(index: Int): MediaItem? {
        if (index < 1) return null
        val items = loadSortedMediaItems()
        val position = index - 1
        if (position >= items.size) return null
        return items[position]
    }

    // ---------- Folder listing with thumbnails ----------

    private fun getAllFoldersWithThumbnails(): List<FolderRow> {
        val all = loadAllMediaItemsUnfiltered()
            .sortedByDescending { it.dateAdded } // so each folder's thumbnail is its newest item
        val excluded = getExcludedFolders()

        val bucketToSample = LinkedHashMap<String, Uri>()
        for (item in all) {
            if (!bucketToSample.containsKey(item.bucketName)) {
                bucketToSample[item.bucketName] = item.uri
            }
        }

        return bucketToSample.entries
            .sortedBy { it.key.lowercase() }
            .map { (name, uri) ->
                FolderRow(name = name, thumbnailUri = uri, included = !excluded.contains(name))
            }
    }

    private fun showFolderPicker() {
        val folders = getAllFoldersWithThumbnails()
        if (folders.isEmpty()) {
            statusText.text = "No folders found."
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_folders, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.folderRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        val adapter = FolderAdapter(this, folders) { _, _ -> /* state kept in list itself */ }
        recyclerView.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle("Include folders")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newExcluded = folders.filter { !it.included }.map { it.name }.toSet()
                saveExcludedFolders(newExcluded)
                statusText.text = "Folder selection saved."
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Select All") { _, _ ->
                saveExcludedFolders(emptySet())
                statusText.text = "All folders included."
            }
            .show()
    }

    // ---------- Number parsing & preview/share actions ----------

    private fun parseNumber(): Int? {
        val text = editNumber.text?.toString()?.trim() ?: ""
        val n = text.toIntOrNull()
        if (n == null || n < 1) {
            statusText.text = "Enter a valid number (1 or higher)."
            return null
        }
        return n
    }

    private fun showPreview() {
        val n = parseNumber() ?: return
        loadAndPreview(n)
    }

    private fun loadAndPreview(n: Int) {
        val item = getItemByNumber(n)
        if (item == null) {
            statusText.text = "No item #$n found (fewer items than that in your included folders)."
            imagePreview.setImageDrawable(null)
            playOverlay.visibility = View.GONE
            return
        }
        statusText.text = ""
        playOverlay.visibility = if (item.isVideo) View.VISIBLE else View.GONE

        try {
            val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(item.uri, Size(800, 800), null)
            } else if (!item.isVideo) {
                contentResolver.openInputStream(item.uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            } else {
                null // pre-API 29 video thumbnail skipped for simplicity; share still works
            }
            imagePreview.setImageBitmap(bitmap)
        } catch (e: Exception) {
            statusText.text = "Couldn't load item #$n."
        }
    }

    private fun pickRandom() {
        val total = loadSortedMediaItems().size
        if (total == 0) {
            statusText.text = "No photos or videos found in your included folders."
            return
        }
        val n = Random.nextInt(1, total + 1)
        editNumber.setText(n.toString())
        loadAndPreview(n)
    }

    private fun shareSelectedItem() {
        val n = parseNumber() ?: return
        val item = getItemByNumber(n)
        if (item == null) {
            statusText.text = "No item #$n found (fewer items than that in your included folders)."
            return
        }
        statusText.text = ""

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share #$n via"))
    }
}
