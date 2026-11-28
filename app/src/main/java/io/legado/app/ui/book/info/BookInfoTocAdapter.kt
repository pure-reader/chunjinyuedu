package io.legado.app.ui.book.info

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ItemBookInfoTocBinding

/**
 * 详情页目录预览：围绕当前阅读章节取一小段，等宽序号 + 章节名，点行进入阅读。
 * 序号是全书真实序号，当前章高亮；完整目录仍由 tv_toc_view 跳转 TocActivity。
 */
class BookInfoTocAdapter(
    private val onClick: (BookChapter) -> Unit,
) : RecyclerView.Adapter<BookInfoTocAdapter.Holder>() {

    private val items = arrayListOf<BookChapter>()
    private var startIndex = 0
    private var currentIndex = -1

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
        val absolute = startIndex + position
        val isCurrent = absolute == currentIndex
        holder.binding.run {
            tvTocIndex.text = (absolute + 1).toString().padStart(2, '0')
            // 书源标题自带 "1.第1章" 时，序号列再显示 01 就是三重编号
            tvTocTitle.text = chapter.displayTitleNoIndex()
            val color = ContextCompat.getColor(
                root.context,
                if (isCurrent) R.color.accent else R.color.primaryText,
            )
            tvTocTitle.setTextColor(color)
            tvTocTitle.typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tvTocIndex.setTextColor(
                ContextCompat.getColor(
                    root.context,
                    if (isCurrent) R.color.accent else R.color.secondaryText,
                )
            )
            root.setOnClickListener { onClick(chapter) }
        }
    }

    fun submit(list: List<BookChapter>, listStartIndex: Int, readingIndex: Int) {
        items.clear()
        items.addAll(list)
        startIndex = listStartIndex
        currentIndex = readingIndex
        notifyDataSetChanged()
    }

}
