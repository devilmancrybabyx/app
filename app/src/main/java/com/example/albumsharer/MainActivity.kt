package com.example.albumsharer

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var editNumber: EditText
    private lateinit var imagePreview: ImageView
    private lateinit var statusText: TextView
    private var pendingAction: (() -> Unit)? = null

    private val prefs by lazy { getSharedPreferences("album_sharer_prefs", MODE_PRIVATE) }

    // Folders the user has unchecked (excluded from selection).
    private fun getExcludedFolders(): MutableSet<String> =
        HashSet(prefs.getStringSet("excluded_folders", emptySet()) ?: emptySet())

    private fun saveExcludedFolders(excluded: Set<String>) {
        prefs.edit().putStringSet("excluded_folders", excluded).apply()
    }

    private val mediaPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_IMAGES
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
        statusText = findViewById(R.id.statusText)
        val btnPreview: Button = findViewById(R.id.btnPreview)
        val btnShare: Button = findViewById(R.id.btnShare)
        val btnRandom: Button = findViewById(R.id.btnRandom)
        val btnFolders: Button = findViewById(R.id.btnFolders)

        btnPreview.setOnClickListener { withPermission { showPreview() } }
        btnShare.setOnClickListener { withPermission { shareSelectedPhoto() } }
        btnRandom.setOnClickListener { withPermission { pickRandom() } }
        btnFolders.setOnClickListener { withPermission { showFolderPicker() } }
    }

    private fun withPermission(action: () -> Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, mediaPermission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (granted) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(mediaPermission)
        }
    }

    // ---------- Folder listing & filtering ----------

    /** All distinct folder (bucket) names photos live in, alphabetically sorted. */
    private fun getAllFolderNames(): List<String> {
        val names = LinkedHashSet<String>()
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                cursor.getString(col)?.let { names.add(it) }
            }
        }
        return names.sorted()
    }

    private fun showFolderPicker() {
        val allFolders = getAllFolderNames()
        if (allFolders.isEmpty()) {
            statusText.text = "No folders found."
            return
        }
        val excluded = getExcludedFolders()
        // checkedItems[i] = true means the folder is INCLUDED
        val checkedItems = BooleanArray(allFolders.size) { i -> !excluded.contains(allFolders[i]) }

        AlertDialog.Builder(this)
            .setTitle("Include folders")
            .setMultiChoiceItems(allFolders.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val newExcluded = allFolders.filterIndexed { i, _ -> !checkedItems[i] }.toSet()
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

    /** Builds a "bucket_display_name NOT IN (?,?,...)" selection clause, or null if nothing excluded. */
    private fun buildExclusionSelection(): Pair<String?, Array<String>?> {
        val excluded = getExcludedFolders()
        if (excluded.isEmpty()) return Pair(null, null)
        val placeholders = excluded.joinToString(",") { "?" }
        val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} NOT IN ($placeholders)"
        return Pair(selection, excluded.toTypedArray())
    }

    // ---------- Photo lookup (respects folder filter) ----------

    private fun getTotalPhotoCount(): Int {
        val (selection, args) = buildExclusionSelection()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args, null
        )?.use { cursor -> return cursor.count }
        return 0
    }

    /**
     * Returns the content:// Uri of the Nth-newest photo, among folders
     * that haven't been excluded. index is 1-based: 1 = newest photo.
     */
    private fun getPhotoUriByNumber(index: Int): Uri? {
        if (index < 1) return null

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, " +
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val (selection, args) = buildExclusionSelection()

        contentResolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
            val position = index - 1
            if (position >= cursor.count) return null
            cursor.moveToPosition(position)
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val id = cursor.getLong(idColumn)
            return ContentUris.withAppendedId(collection, id)
        }
        return null
    }

    private fun parseNumber(): Int? {
        val text = editNumber.text.toString().trim()
        val n = text.toIntOrNull()
        if (n == null || n < 1) {
            statusText.text = "Enter a valid photo number (1 or higher)."
            return null
        }
        return n
    }

    private fun showPreview() {
        val n = parseNumber() ?: return
        loadAndPreview(n)
    }

    private fun loadAndPreview(n: Int) {
        val uri = getPhotoUriByNumber(n)
        if (uri == null) {
            statusText.text = "No photo #$n found (fewer photos than that in your included folders)."
            imagePreview.setImageDrawable(null)
            return
        }
        statusText.text = ""
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                imagePreview.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            statusText.text = "Couldn't load photo #$n."
        }
    }

    private fun pickRandom() {
        val total = getTotalPhotoCount()
        if (total == 0) {
            statusText.text = "No photos found in your included folders."
            return
        }
        val n = Random.nextInt(1, total + 1) // 1-based, inclusive of total
        editNumber.setText(n.toString())
        loadAndPreview(n)
    }

    private fun shareSelectedPhoto() {
        val n = parseNumber() ?: return
        val uri = getPhotoUriByNumber(n)
        if (uri == null) {
            statusText.text = "No photo #$n found (fewer photos than that in your included folders)."
            return
        }
        statusText.text = ""

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share photo #$n via"))
    }
}
