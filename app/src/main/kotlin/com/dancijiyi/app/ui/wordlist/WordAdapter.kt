package com.dancijiyi.app.ui.wordlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dancijiyi.app.R
import com.dancijiyi.domain.model.WordWithState
import java.time.Instant

/**
 * 单词列表适配器。
 *
 * 用 [ListAdapter] + DiffUtil：导入/删除后只做增量刷新，不会整列表重建，
 * 也不会因为 `notifyDataSetChanged` 丢掉滚动位置。
 */
class WordAdapter(
    private val onClick: (wordId: Long) -> Unit,
) : ListAdapter<WordWithState, WordAdapter.WordViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_word, parent, false)
        return WordViewHolder(view)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class WordViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val spelling: android.widget.TextView = view.findViewById(R.id.wordSpelling)
        private val phonetic: android.widget.TextView = view.findViewById(R.id.wordPhonetic)
        private val meaning: android.widget.TextView = view.findViewById(R.id.wordMeaning)
        private val badge: android.widget.TextView = view.findViewById(R.id.wordBadge)

        fun bind(item: WordWithState, onClick: (Long) -> Unit) {
            spelling.text = item.word.spelling
            phonetic.text = item.word.phonetic ?: ""
            phonetic.visibility = if (item.word.phonetic.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            meaning.text = item.word.meaning

            val state = item.state
            val now = Instant.now()
            when {
                state.isMastered -> {
                    badge.text = "✓ 已掌握"
                    badge.setTextColor(0xFF2E9E5B.toInt())
                }

                state.reviewCount == 0 -> {
                    badge.text = "新词"
                    badge.setTextColor(0xFF3B82F6.toInt())
                }

                !state.nextDueAt.isAfter(now) -> {
                    badge.text = "● 到期"
                    badge.setTextColor(0xFFD9534F.toInt())
                }

                else -> {
                    badge.text = "·"
                    badge.setTextColor(0xFFAAAAAA.toInt())
                }
            }

            itemView.setOnClickListener { onClick(item.word.id) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<WordWithState>() {
            override fun areItemsTheSame(oldItem: WordWithState, newItem: WordWithState): Boolean =
                oldItem.word.id == newItem.word.id

            override fun areContentsTheSame(oldItem: WordWithState, newItem: WordWithState): Boolean =
                oldItem == newItem
        }
    }
}
