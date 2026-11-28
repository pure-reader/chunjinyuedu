package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.activity.viewModels
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.charsets
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityBookReadBinding
import io.legado.app.databinding.DialogDownloadChoiceBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogSimulatedReadingBinding
import io.legado.app.help.book.cacheLocalUri
import io.legado.app.help.book.savePreservingCustomCoverUrl
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.read.config.BgTextConfigDialog
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.PaddingConfigDialog
import io.legado.app.ui.book.read.config.PageKeyDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.archive.ArchiveCodeLabel
import io.legado.app.ui.widget.archive.ArchiveCurtainView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getPrefString
import io.legado.app.utils.gone
import io.legado.app.utils.isTv
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

@SuppressLint("InflateParams", "SetTextI18n")
fun Context.showBookDownloadDialog(book: Book) {
    alert(titleResource = R.string.offline_cache) {
        val alertBinding = DialogDownloadChoiceBinding
            .inflate(LayoutInflater.from(this@showBookDownloadDialog))
            .apply {
                editStart.setText((book.durChapterIndex + 1).toString())
                editEnd.setText(book.totalChapterNum.toString())
            }
        customView { alertBinding.root }
        okButton {
            alertBinding.run {
                val start = editStart.text!!.toString().let {
                    if (it.isEmpty()) 0 else it.toInt()
                }
                val end = editEnd.text!!.toString().let {
                    if (it.isEmpty()) book.totalChapterNum else it.toInt()
                }
                CacheBook.start(this@showBookDownloadDialog, book, start - 1, end - 1)
            }
        }
        cancelButton()
    }
}

/**
 * 阅读界面
 */
abstract class BaseReadBookActivity :
    VMBaseActivity<ActivityBookReadBinding, ReadBookViewModel>(imageBg = false) {

    override val binding by viewBinding(ActivityBookReadBinding::inflate)
    override val viewModel by viewModels<ReadBookViewModel>()
    protected val menuLayoutIsVisible
        get() = bottomDialog > 0 || binding.readMenu.isVisible || binding.searchMenu.bottomMenuVisible

    private var entranceAnimator: ValueAnimator? = null
    private var entranceCurtain: ArchiveCurtainView? = null
    private var entrancePage: View? = null
    private var entranceRevealed = false

    var bottomDialog = 0
        set(value) {
            if (field != value) {
                field = value
                onBottomDialogChange()
            }
        }
    private val selectBookFolderResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ReadBook.book?.let { book ->
                FileDoc.fromUri(uri, true).find(book.originName)?.let { doc ->
                    AppConfig.importBookPath = uri.toString()
                    LocalBook.withParserCacheInvalidated(book) {
                        book.cacheLocalUri(doc.uri)
                    }
                    viewModel.loadChapterList(book)
                } ?: ReadBook.upMsg("找不到文件")
            }
        } ?: ReadBook.upMsg("没有权限访问")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ReadBook.msg = null
        setOrientation()
        upLayoutInDisplayCutoutMode()
        super.onCreate(savedInstanceState)
        binding.navigationBar.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams {
                height = insets.bottom
            }
            windowInsets
        }
        if (savedInstanceState == null) prepareArchiveEntrance()
    }

    /**
     * 档案风入场：纸幕按水平条带错峰流走，与正文翻页的"档案流动"完全统一。
     * 时长 620ms，外层线性驱动、条带内部 easeInOutSine——与 RelayPageDelegate 同一套时钟。
     *
     * 纸幕在 onCreate 就铺上并保持不透明，等首章内容真正落到页面上
     * （ReadBookActivity.upContent 的 relativePosition == 0）再流走，
     * 否则加载态的"加载中"会把动画吃掉。超时兜底 2.6s。
     */
    private fun prepareArchiveEntrance() {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val page = content.getChildAt(0) ?: return
        val curtain = ArchiveCurtainView(this)
        val base = getCompatColor(R.color.background)
        val dark = androidx.core.graphics.ColorUtils.calculateLuminance(base) < 0.35
        curtain.paperColor = if (dark) 0xFF20201E.toInt() else 0xFFE4E0D4.toInt()
        curtain.inkColor = getCompatColor(R.color.primaryText)
        curtain.isClickable = false
        curtain.isFocusable = false
        curtain.progress = 0f
        content.addView(
            curtain,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        entranceCurtain = curtain
        entrancePage = page
        curtain.postDelayed({ revealArchiveEntrance() }, 2600L)
    }

    /** 揭开入场纸幕；重复调用无效。 */
    fun revealArchiveEntrance() {
        if (entranceRevealed) return
        entranceRevealed = true
        val curtain = entranceCurtain ?: return
        val page = entrancePage ?: return
        val content = curtain.parent as? ViewGroup ?: return
        // 幕布上印当前书的档案封套信息，流走时随纸带一起被切走
        ReadBook.book?.let { book ->
            curtain.label = book.name
            curtain.fileCode = ArchiveCodeLabel.fileCode(
                book.bookUrl.hashCode().absoluteValue % 100000
            )
        }
        // 外层线性推进，条带内部的错峰起跑 + easeInOutSine 由 ArchiveCurtainView 自己完成
        val animator = ValueAnimator.ofFloat(0f, 1f).setDuration(620L)
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener {
            val progress = it.animatedValue as Float
            curtain.progress = progress
            curtain.applyLanding(page)
        }
        animator.doOnEnd {
            page.translationX = 0f
            page.translationY = 0f
            content.removeView(curtain)
            entranceCurtain = null
        }
        animator.doOnCancel {
            page.translationX = 0f
            page.translationY = 0f
            content.removeView(curtain)
            entranceCurtain = null
        }
        entranceAnimator = animator
        animator.start()
    }

    override fun onDestroy() {
        entranceAnimator?.cancel()
        entranceAnimator = null
        super.onDestroy()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.navigationBar.setBackgroundColor(bottomBackground)
        viewModel.permissionDenialLiveData.observe(this) {
            selectBookFolderResult.launch {
                mode = HandleFileContract.DIR_SYS
                title = "选择书籍所在文件夹"
            }
        }
        if (!LocalConfig.readHelpVersionIsLast) {
            if (isTv) {
                showCustomPageKeyConfig()
            } else {
                showClickRegionalConfig()
            }
        }
    }

    private fun onBottomDialogChange() {
        when (bottomDialog) {
            // A dialog can return to the reading menu before it is dismissed.
            0 -> if (menuLayoutIsVisible) onMenuShow() else onMenuHide()
            1 -> onMenuShow()
        }
    }

    open fun onMenuShow() {

    }

    open fun onMenuHide() {

    }

    fun showPaddingConfig() {
        showDialogFragment<PaddingConfigDialog>()
    }

    fun showBgTextConfig() {
        showDialogFragment<BgTextConfigDialog>()
    }

    fun showClickRegionalConfig() {
        showDialogFragment<ClickActionConfigDialog>()
    }

    private fun showCustomPageKeyConfig() {
        PageKeyDialog(this).show()
    }

    /**
     * 屏幕方向
     */
    @SuppressLint("SourceLockedOrientationActivity")
    fun setOrientation() {
        when (AppConfig.screenOrientation) {
            "0" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            "1" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "2" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "3" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            "4" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "5" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }

    /**
     * 更新状态栏,导航栏
     */
    fun upSystemUiVisibility(
        isInMultiWindow: Boolean,
        toolBarHide: Boolean = true,
        useBgMeanColor: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.run {
                if (toolBarHide && ReadBookConfig.hideNavigationBar) {
                    hide(WindowInsets.Type.navigationBars())
                } else {
                    show(WindowInsets.Type.navigationBars())
                }
                if (toolBarHide && ReadBookConfig.hideStatusBar) {
                    hide(WindowInsets.Type.statusBars())
                } else {
                    show(WindowInsets.Type.statusBars())
                }
            }
        }
        upSystemUiVisibilityO(isInMultiWindow, toolBarHide)
        if (toolBarHide) {
            setLightStatusBar(ReadBookConfig.durConfig.curStatusIconDark())
        } else {
            val statusBarColor =
                if (AppConfig.readBarStyleFollowPage
                    && ReadBookConfig.durConfig.curBgType() == 0
                    || useBgMeanColor
                ) {
                    ReadBookConfig.bgMeanColor
                } else {
                    ThemeStore.statusBarColor(this, AppConfig.isTransparentStatusBar)
                }
            setLightStatusBar(ColorUtils.isColorLight(statusBarColor))
        }
    }

    @Suppress("DEPRECATION")
    private fun upSystemUiVisibilityO(
        isInMultiWindow: Boolean,
        toolBarHide: Boolean = true
    ) {
        var flag = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        if (!isInMultiWindow) {
            flag = flag or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (ReadBookConfig.hideNavigationBar) {
            flag = flag or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (toolBarHide) {
                flag = flag or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            }
        }
        if (ReadBookConfig.hideStatusBar && toolBarHide) {
            flag = flag or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        window.decorView.systemUiVisibility = flag
    }

    override fun upNavigationBarColor() {
        upNavigationBar()
        when {
            binding.readMenu.isVisible -> super.upNavigationBarColor()
            binding.searchMenu.bottomMenuVisible -> super.upNavigationBarColor()
            bottomDialog > 0 -> super.upNavigationBarColor()
            !AppConfig.immNavigationBar -> super.upNavigationBarColor()
            else -> setNavigationBarColorAuto(ReadBookConfig.bgMeanColor)
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun upNavigationBar() {
        binding.navigationBar.gone(!menuLayoutIsVisible)
    }

    /**
     * 保持亮屏
     */
    fun keepScreenOn(on: Boolean) {
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 适配刘海
     */
    private fun upLayoutInDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (ReadBookConfig.readBodyToLh) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                }
            }
        }
    }

    fun showDownloadDialog() {
        ReadBook.book?.let { showBookDownloadDialog(it) }
    }

    fun showSimulatedReading() {
        val book = ReadBook.book ?: return
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val alertBinding = DialogSimulatedReadingBinding.inflate(layoutInflater).apply {
            srEnabled.isChecked = book.getReadSimulating()
            editStart.setText(book.getStartChapter().toString())
            editNum.setText(book.getDailyChapters().toString())
            startDate.setText(book.getStartDate()?.format(dateFormatter))
            startDate.isFocusable = false // 设置为false，不允许获得焦点
            startDate.isCursorVisible = false // 不显示光标
            startDate.setOnClickListener {
                // 获取当前日期
                val localStartDate = runCatching {
                    LocalDate.parse(startDate.text)
                }.getOrDefault(LocalDate.now())
                // 创建 DatePickerDialog
                val datePickerDialog = DatePickerDialog(
                    root.context,
                    { _, yy, mm, dayOfMonth ->
                        // 使用Java 8的日期和时间API来格式化日期
                        val date = LocalDate.of(yy, mm + 1, dayOfMonth) // Java 8的LocalDate，月份从1开始
                        val formattedDate = date.format(dateFormatter)
                        startDate.setText(formattedDate)
                    }, localStartDate.year,
                    localStartDate.monthValue - 1,
                    localStartDate.dayOfMonth
                )
                datePickerDialog.show()
            }
        }
        alert(titleResource = R.string.simulated_reading) {
            customView { alertBinding.root }
            okButton {
                alertBinding.run {
                    val start = editStart.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val num = editNum.text.toString().toIntOrNull()?.coerceAtLeast(1)
                        ?: book.totalChapterNum.coerceAtLeast(1)
                    val enabled = srEnabled.isChecked
                    val date = startDate.text.toString().let {
                        if (it.isEmpty()) LocalDate.now()
                        else runCatching { LocalDate.parse(it, dateFormatter) }
                            .getOrDefault(LocalDate.now())
                    }
                    book.setStartDate(date)
                    book.setDailyChapters(num)
                    book.setStartChapter(start)
                    book.setReadSimulating(enabled)
                    book.savePreservingCustomCoverUrl()
                    ReadBook.clearTextChapter()
                    viewModel.initData(intent)
                }
            }
            cancelButton()
        }
    }

    fun showCharsetConfig() {
        alert(R.string.set_charset) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "charset"
                editView.setFilterValues(charsets)
                editView.setText(ReadBook.book?.charset)
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    ReadBook.setCharset(it)
                }
            }
            cancelButton()
        }
    }

    fun showPageAnimConfig(success: () -> Unit) {
        val items = arrayListOf<String>()
        items.add(getString(R.string.btn_default_s))
        items.add(getString(R.string.page_anim_cover))
        items.add(getString(R.string.page_anim_slide))
        items.add(getString(R.string.page_anim_simulation))
        items.add(getString(R.string.page_anim_scroll))
        items.add(getString(R.string.page_anim_none))
        items.add(getString(R.string.page_anim_paper))
        items.add(getString(R.string.page_anim_flow))
        selector(R.string.page_anim, items) { _, i ->
            ReadBook.book?.setPageAnim(i - 1)
            success()
        }
    }

    fun isPrevKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val prevKeysStr = getPrefString(PreferKey.prevKeys)
        return prevKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }

    fun isNextKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val nextKeysStr = getPrefString(PreferKey.nextKeys)
        return nextKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }
}
