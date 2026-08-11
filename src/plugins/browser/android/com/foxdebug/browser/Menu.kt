package com.foxdebug.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.foxdebug.acode.R
import com.foxdebug.system.Ui

class Menu(
    private val context: Context,
    private val theme: Ui.Theme
) : PopupWindow(context) {

    fun interface Callback {
        fun onSelect(action: String, checked: Boolean?)
    }

    private val list: LinearLayout
    private var callback: Callback? = null

    val padding: Int = Ui.dpToPixels(context, 5)
    val imageSize: Int = Ui.dpToPixels(context, 30)
    val itemHeight: Int = Ui.dpToPixels(context, 40)

    init {
        val border = GradientDrawable().apply {
            setColor(theme.get("popupBackgroundColor"))
            cornerRadius = Ui.dpToPixels(context, 8).toFloat()
        }

        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL休
            background = border
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val scrollView = ScrollView(context).apply {
            addView(list)
        }

        elevation = 10f
        isFocusable = true
        contentView = scrollView
        setBackgroundDrawable(border)
        animationStyle = R.style.MenuAnimation
    }

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    @JvmOverloads
    fun addItem(icon: String, text: String, toggle: Boolean? = null) {
        val textColor = theme.get("popupTextColor")
        val background = theme.get("popupBackgroundColor")

        val menuItem = MenuItem(this, text).apply {
            setBackgroundColor(background)
            setIcon(icon, textColor)
            setText(text, textColor)
            setMenuItemClickListener { item ->
                callback?.onSelect(item.action, item.checked)
                hide()
            }
            toggle?.let { setChecked(it) }
        }

        list.addView(menuItem)
    }

    fun setChecked(action: String, checked: Boolean) {
        for (i in 0 until list.childCount) {
            val menuItem = list.getChildAt(i) as? MenuItem ?: continue
            if (menuItem.action == action) {
                menuItem.setChecked(checked)
                break
            }
        }
    }

    fun setVisible(action: String, visible: Boolean) {
        for (i in 0 until list.childCount) {
            val menuItem = list.getChildAt(i) as? MenuItem ?: continue
            if (menuItem.action == action) {
                menuItem.visibility = if (visible) View.VISIBLE else View.GONE
                break
            }
        }
    }

    fun show(view: View) {
        showAtLocation(view, Gravity.TOP or Gravity.RIGHT, padding, padding)
    }

    fun hide() {
        dismiss()
    }

    class MenuItem(menu: Menu, val action: String) : LinearLayout(menu.context) {

        private val itemContext: Context = menu.context
        private val padding: Int = menu.padding
        private val imageSize: Int = menu.imageSize
        private val itemHeight: Int = menu.itemHeight

        var checked: Boolean? = null
            private set

        private var checkBox: CheckBox? = null
        private var textColor: Int = 0
        private var paddingLeft: Int = 0
        private var paddingRight: Int = menu.imageSize
        private var paddingVertical: Int = Ui.dpToPixels(itemContext, 5)

        init {
            updatePadding()
            isClickable = true
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                itemHeight
            )
            gravity = Gravity.CENTER_VERTICAL
            orientation = HORIZONTAL
        }

        private fun updatePadding() {
            setPadding(paddingLeft, paddingVertical, paddingRight, paddingVertical)
        }

        fun setIcon(icon: String, iconColor: Int) {
            val iconBitmap = Ui.Icons.get(itemContext, icon, iconColor)
            val imageView = ImageView(itemContext).apply {
                setImageBitmap(iconBitmap)
                background = null
                layoutParams = LayoutParams(imageSize, imageSize)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setPadding(padding, padding, padding, padding)
            }
            addView(imageView, 0)
        }

        fun setText(text: String, color: Int) {
            textColor = color
            val textView = TextView(itemContext).apply {
                this.text = text
                setTextColor(textColor)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                )
            }
            addView(textView, if (childCount > 0) 1 else 0)
        }

        fun setChecked(checked: Boolean) {
            this.checked = checked
            val existingCheckBox = checkBox
            if (existingCheckBox != null) {
                existingCheckBox.isChecked = checked
                return
            }

            val cb = CheckBox(itemContext).apply {
                isChecked = checked
                isEnabled = false
                isClickable = false
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(-android.R.attr.state_checked)
                    ),
                    intArrayOf(textColor, textColor)
                )
            }
            this.checkBox = cb

            val container = FrameLayout(itemContext).apply {
                layoutParams = FrameLayout.LayoutParams(imageSize, imageSize).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                addView(cb)
            }

            paddingRight = 0
            updatePadding()
            addView(container)
        }

        fun setMenuItemClickListener(onClick: (MenuItem) -> object) {
            setOnClickListener {
                if (checkBox != null) {
                    val current = checked ?: false
                    setChecked(!current)
                }
                onClick(this)
            }
        }
    }
}
