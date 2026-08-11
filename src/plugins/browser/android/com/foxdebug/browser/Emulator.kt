package com.foxdebug.browser

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.foxdebug.system.Ui

class Emulator(
    context: Context,
    private val theme: Ui.Theme
) : LinearLayout(context) {

    fun interface Callback {
        fun onChange(width: Int, height: Int, scale: Float)
    }

    private var listener: Callback? = null
    private val customDevice = Device("Custom", 0, 0, Ui.Icons.TUNE, false)
    private var selectedDevice: Device? = null
    private var initialized = false
    private val seekBarsLayout: LinearLayout
    private val deviceListView: DeviceListView
    private val seekBars = HashMap<String, SeekBar>()

    init {
        orientation = VERTICAL

        deviceListView = DeviceListView(context, theme).apply {
            layoutParams = LayoutParams(
                Ui.dpToPixels(context, 100),
                LayoutParams.MATCH_PARENT
            )
            setOnSelect { device -> selectDevice(device) }
            add(
                customDevice,
                Device("iPhone SE", 320, 568, Ui.Icons.PHONE_APPLE),
                Device("iPhone 8", 375, 667, Ui.Icons.PHONE_APPLE),
                Device("iPhone 8+", 414, 736, Ui.Icons.PHONE_APPLE),
                Device("iPhone X", 375, 812, Ui.Icons.PHONE_APPLE),
                Device("iPad", 768, 1024, Ui.Icons.TABLET_APPLE),
                Device("iPad Pro", 1024, 1366, Ui.Icons.TABLET_APPLE),
                Device("Galaxy S5", 360, 640, Ui.Icons.PHONE_ANDROID),
                Device("Pixel 2", 411, 731, Ui.Icons.PHONE_ANDROID),
                Device("Pixel 2 XL", 411, 823, Ui.Icons.PHONE_ANDROID),
                Device("Nexus 5X", 411, 731, Ui.Icons.PHONE_ANDROID),
                Device("Nexus 6P", 411, 731, Ui.Icons.PHONE_ANDROID),
                Device("Nexus 7", 600, 960, Ui.Icons.TABLET_ANDROID),
                Device("Nexus 10", 800, 1280, Ui.Icons.TABLET_ANDROID),
                Device("Laptop", 1280, 800, Ui.Icons.LAPTOP),
                Device("Laptop L", 1440, 900, Ui.Icons.LAPTOP),
                Device("Laptop XL", 1680, 1050, Ui.Icons.LAPTOP),
                Device("UHD 4k", 3840, 2160, Ui.Icons.TV)
            )
            select(customDevice)
        }

        seekBarsLayout = LinearLayout(context).apply {
            setPadding(0, 10, 0, 0)
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val border = View(context).apply {
            setBackgroundColor(theme.get("borderColor"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
        }

        val main = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.get("primaryColor"))
            addView(seekBarsLayout)
            addView(deviceListView)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }

        addControl("width", 50, "Width")
        addControl("height", 50, "Height")
        addControl("scale", 50, "Scale")

        addView(border)
        addView(main)
    }

    fun setChangeListener(listener: Callback?) {
        this.listener = listener
    }

    fun setReference(view: View) {
        val widthSeekBar = seekBars["width"] ?: return
        val heightSeekBar = seekBars["height"] ?: return
        val scaleSeekBar = seekBars["scale"] ?: return

        val maxWidth = view.measuredWidth
        val maxHeight = view.measuredHeight
        val width = widthSeekBar.progress
        val height = heightSeekBar.progress

        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                val correctedHeight = maxHeight - this@Emulator.height
                var iHeight = height
                var iWidth = width

                widthSeekBar.max = maxWidth
                heightSeekBar.max = correctedHeight

                if (width > maxWidth || !initialized) {
                    heightSeekBar.progress = maxHeight
                    iHeight = maxHeight
                }

                if (height > correctedHeight || !initialized) {
                    widthSeekBar.progress = maxWidth
                    iWidth = maxWidth
                }

                setMaxScale(iWidth, iHeight)
                scaleSeekBar.setMin(100)
                scaleSeekBar.progress = 100
                listener?.onChange(iWidth, correctedHeight, 1f)
            }
        })
    }

    val widthProgress: Int
        get() = seekBars["width"]?.progress ?: 0

    val heightProgress: Int
        get() = seekBars["height"]?.progress ?: 0

    val scaleProgress: Float
        get() = (seekBars["scale"]?.progress ?: 100) / 100f

    private fun addControl(id: String, height: Int, label: String) {
        val linearLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }

        val textView = TextView(context).apply {
            text = String.format(label, 0)
            setPadding(10, 0, 0, 0)
        }

        val seekBar = SeekBar(context).apply {
            setMin(300)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                height,
                1f
            )
        }

        linearLayout.addView(textView)
        linearLayout.addView(seekBar)
        seekBarsLayout.addView(linearLayout)
        seekBars[id] = seekBar

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || listener == null) return

                val seekBarName = when (seekBar) {
                    seekBars["width"] -> "width"
                    seekBars["height"] -> "height"
                    else -> "scale"
                }

                Log.d("Emulator", seekBarName)

                val h = heightProgress
                val w = widthProgress
                var s = scaleProgress

                if (seekBarName != "scale") {
                    setMaxScale(w, h)
                    s = 1f
                }

                listener?.onChange(w, h, s)

                if (seekBarName == "scale" || selectedDevice == null || selectedDevice?.id == customDevice.id) {
                    return
                }

                selectDevice(customDevice)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun selectDevice(device: Device) {
        selectedDevice?.deselect()
        device.select()

        selectedDevice = device
        if (device.id == customDevice.id) return

        val widthSeekBar = seekBars["width"] ?: return
        val heightSeekBar = seekBars["height"] ?: return
        val scaleSeekBar = seekBars["scale"] ?: return

        val maxWidth = widthSeekBar.max
        val maxHeight = heightSeekBar.max

        var width = device.width
        var height = device.height

        if (width > maxWidth) {
            val ratio = (width - maxWidth) / width.toFloat()
            width = maxWidth
            height = (height - (height * ratio)).toInt()
        }

        if (height > maxHeight) {
            val ratio = (height - maxHeight) / height.toFloat()
            height = maxHeight
            width = (width - (width * ratio)).toInt()
        }

        widthSeekBar.progress = width
        heightSeekBar.progress = height
        setMaxScale(width, height)
        val maxScale = scaleSeekBar.max
        scaleSeekBar.progress = maxScale
        listener?.onChange(width, height, maxScale / 100f)
    }

    private fun setMaxScale(width: Int, height: Int) {
        val scaleSeekBar = seekBars["scale"] ?: return
        val widthSeekBar = seekBars["width"] ?: return
        val heightSeekBar = seekBars["height"] ?: return

        val maxWidth = widthSeekBar.max
        val maxHeight = heightSeekBar.max

        val scaleX = maxWidth / width.toFloat()
        val scaleY = maxHeight / height.toFloat()
        val scale = (Math.min(scaleX, scaleY) * 100).toInt()

        scaleSeekBar.max = scale
        scaleSeekBar.progress = 100
    }
}

class Device @JvmOverloads constructor(
    val name: String,
    val width: Int,
    val height: Int,
    val icon: String = Ui.Icons.TUNE,
    val isDesktop: Boolean = true
) {
    val id: Int = View.generateViewId()
    var view: DeviceView? = null

    fun select() {
        view?.select()
    }

    fun deselect() {
        view?.deselect()
    }
}

class DeviceListView(
    context: Context,
    private val theme: Ui.Theme
) : ScrollView(context) {

    fun interface Callback {
        fun onSelect(device: Device)
    }

    private var selectedDeviceView: DeviceView? = null
    private val deviceListLayout: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
    }
    private var callback: Callback? = null

    init {
        addView(deviceListLayout)
    }

    fun add(vararg devices: Device) {
        for (device in devices) {
            add(device)
        }
    }

    fun select(device: Device) {
        val deviceView = findViewById<DeviceView>(device.id) ?: return
        deviceView.select()
        selectedDeviceView = deviceView
    }

    fun add(device: Device) {
        val deviceView = DeviceView(context, device, theme)
        deviceListLayout.addView(deviceView)

        deviceView.setOnSelect { view ->
            val cb = callback ?: return@setOnSelect
            selectedDeviceView?.deselect()
            view.select()
            cb.onSelect(view.device)
            selectedDeviceView = view
        }
    }

    fun setOnSelect(callback: Callback) {
        this.callback = callback
    }
}

class DeviceView(
    context: Context,
    val device: Device,
    private val theme: Ui.Theme
) : LinearLayout(context) {

    fun interface Callback {
        fun onSelect(device: DeviceView)
    }

    private val label: TextView
    private val icon: ImageView
    var isSelectedDevice = false
        private set

    init {
        val primaryTextColor = theme.get("primaryTextColor")
        device.view = this

        id = device.id
        isClickable = true
        setPadding(0, 5, 0, 5)
        orientation = HORIZONTAL

        icon = ImageView(context).apply {
            setImageBitmap(Ui.Icons.get(context, device.icon, primaryTextColor))
            setPadding(0, 0, 5, 0)
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }

        label = TextView(context).apply {
            isSingleLine = true
            text = device.name
            setTextColor(primaryTextColor)
        }

        addView(icon)
        addView(label)
    }

    fun setOnSelect(callback: Callback) {
        setOnClickListener { callback.onSelect(this) }
    }

    fun deselect() {
        val primaryTextColor = theme.get("primaryTextColor")
        icon.setImageBitmap(Ui.Icons.get(context, device.icon, primaryTextColor))
        label.setTextColor(primaryTextColor)
        label.setTypeface(null, Typeface.NORMAL)
        isSelectedDevice = false
    }

    fun select() {
        val activeTextColor = theme.get("activeTextColor")
        icon.setImageBitmap(Ui.Icons.get(context, device.icon, activeTextColor))
        label.setTextColor(activeTextColor)
        label.setTypeface(null, Typeface.BOLD)
        isSelectedDevice = true
    }
}
