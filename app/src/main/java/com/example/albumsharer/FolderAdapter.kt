package com.example.albumsharer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** One folder row: its display name, a sample photo Uri for the thumbnail, and included/excluded state. */
data class FolderRow(
    val name: String,
    val thumbnailUri: Uri?,
    var included: Boolean
)

class FolderAdapter(
    private val context: Context,
    private val items: List<FolderRow>,
    private val onToggle: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    // Simple in-memory cache so scrolling back doesn't reload thumbnails.
    private val thumbCache = HashMap<Uri, Bitmap>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.folderThumbnail)
        val name: TextView = view.findViewById(R.id.folderName)
        val checkbox: CheckBox = view.findViewById(R.id.folderCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.dialog_folder_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.checkbox.isChecked = item.included
        holder.thumbnail.setImageDrawable(null)

        holder.itemView.setOnClickListener {
            val newState = !holder.checkbox.isChecked
            holder.checkbox.isChecked = newState
            items[position].included = newState
            onToggle(position, newState)
        }

        val uri = item.thumbnailUri ?: return
        thumbCache[uri]?.let {
            holder.thumbnail.setImageBitmap(it)
            return
        }

        executor.execute {
            val bitmap = try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(uri, Size(160, 160), null)
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                thumbCache[uri] = bitmap
                mainHandler.post {
                    // Only apply if this holder hasn't been recycled for another item.
                    if (holder.adapterPosition == position) {
                        holder.thumbnail.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}
