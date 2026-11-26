package io.legado.app.ui.book.info

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ItemBookInfoTocBinding

/**
 * 详情页目录预览：只列前 TOC_PREVIEW_COUNT 章，等宽序号 + 章节名，点行进入阅读。
 * 完整目录仍由 tv_toc_view 跳转 TocActivity。
 */
class BookInfoTocAdapter(
    private val onClick: (BookChapter) -> Unit,
) : RecyclerView.Adapter<BookInfoTocAdapter.Holder>() {

    private val items = arrayListOf<BookChapter>()

    class Holder(val binding: ItemBookInfoTocBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            ItemBookInfoTocBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val chapter = items[position]
        holder.binding.run {
            tvTocIndex.text = (position + 1).toString().padStart(2, '0')
            tvTocTitle.text = chapter.title
            root.setOnClickListener { onClick(chapter) }
        }
    }

    fun submit(list: List<BookChapter>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

}
