package com.car.mp3player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.car.mp3player.databinding.ItemArtistBinding
import com.car.mp3player.model.FolderGroup

class FolderAdapter(
    private val onClick: (FolderGroup) -> Unit
) : ListAdapter<FolderGroup, FolderAdapter.FolderViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder =
        FolderViewHolder(
            ItemArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FolderViewHolder(
        private val binding: ItemArtistBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: FolderGroup) {
            binding.artistName.text = "📁 ${group.name}"
            binding.artistCount.text =
                binding.root.context.getString(R.string.artist_song_count, group.songCount)
            binding.root.setOnClickListener { onClick(group) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FolderGroup>() {
            override fun areItemsTheSame(oldItem: FolderGroup, newItem: FolderGroup) =
                oldItem.path == newItem.path
            override fun areContentsTheSame(oldItem: FolderGroup, newItem: FolderGroup) =
                oldItem == newItem
        }
    }
}
