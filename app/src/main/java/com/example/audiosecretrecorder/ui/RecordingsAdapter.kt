package com.example.audiosecretrecorder.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.audiosecretrecorder.R
import com.example.audiosecretrecorder.model.RecordingModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsAdapter(
    private var recordings: List<RecordingModel>,
    private val onPlayClick: (RecordingModel) -> Unit,
    private val onShareClick: (RecordingModel) -> Unit,
    private val onDeleteClick: (RecordingModel) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvFileName: TextView = itemView.findViewById(R.id.tv_file_name)
        val tvFileInfo: TextView = itemView.findViewById(R.id.tv_file_info)
        val btnPlayPause: ImageButton = itemView.findViewById(R.id.btn_play_pause)
        val btnShare: ImageButton = itemView.findViewById(R.id.btn_share)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = recordings[position]
        holder.tvFileName.text = item.name

        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.dateAdded))
        holder.tvFileInfo.text = "${item.formattedDuration} • ${item.formattedSize} • $dateStr"

        if (item.isPlaying) {
            holder.btnPlayPause.setImageResource(R.drawable.ic_pause)
        } else {
            holder.btnPlayPause.setImageResource(R.drawable.ic_play)
        }

        holder.btnPlayPause.setOnClickListener { onPlayClick(item) }
        holder.btnShare.setOnClickListener { onShareClick(item) }
        holder.btnDelete.setOnClickListener { onDeleteClick(item) }
    }

    override fun getItemCount(): Int = recordings.size

    fun updateList(newList: List<RecordingModel>) {
        recordings = newList
        notifyDataSetChanged()
    }
}

