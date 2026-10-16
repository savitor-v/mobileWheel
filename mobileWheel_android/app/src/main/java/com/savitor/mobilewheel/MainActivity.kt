package com.savitor.mobilewheel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    // UI组件 - 使用懒加载
    private val tvSensorStatus: TextView by lazy { findViewById(R.id.tvSensorStatus) }
    private val tvLocalIp: TextView by lazy { findViewById(R.id.tvLocalIp) }
    private val tvLanStatus: TextView by lazy { findViewById(R.id.tvLanStatus) }
    private val levelView: LevelView by lazy { findViewById(R.id.levelView) }

    // 顶部两个模拟手柄按键：左 X、右 A
    private val btnX: Button by lazy { findViewById(R.id.btnX) }
    private val btnA: Button by lazy { findViewById(R.id.btnA) }

    // 左右两侧的扳机操控框，模拟 Xbox 360 手柄的 LT / RT 扳机（0..255）
    private val padLt: TriggerPadView by lazy { findViewById(R.id.padLt) }
    private val padRt: TriggerPadView by lazy { findViewById(R.id.padRt) }
    private val tvLtValue: TextView by lazy { findViewById(R.id.tvLtValue) }
    private val tvRtValue: TextView by lazy { findViewById(R.id.tvRtValue) }

    // 最近一次的倾角读数：扳机 / 按键变化时用它补发一帧
    private var lastAngle = 0f
    private var lastDirection = "水平"

    // X / A 按键当前是否按下
    private var isXPressed = false
    private var isAPressed = false

    // 局域网：把倾斜角按行通过 TCP 发送给 Windows 客户端
    private val netServer by lazy { TiltNetServer() }
    private var isLanOn = false

    // 震动：电脑端把虚拟手柄收到的震动强度回传过来，这里按幅度驱动手机振动
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val rumbleHandler = Handler(Looper.getMainLooper())
    private var rumbleAmplitude = 0     // 0..255，0 表示不震
    private var rumbleRunning = false
    private var lastRumbleAt = 0L       // 最近一次收到震动强度的时刻，用于掉线兜底

    // 上一次发送数据的时刻，用于按固定间隔限流
    private var lastSentAt = 0L

    // 通知权限只请求一次
    private var notificationPermissionAsked = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "未授予通知权限：后台服务仍会运行，但不显示常驻通知", Toast.LENGTH_SHORT).show()
            }
        }

    // 传感器管理器
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    // 无重力传感器时退回加速度计，需要通过低通滤波提取重力分量
    private var needLowPassFilter = false

    // 重力方向（设备自然坐标系）
    private val gravity = FloatArray(3)

    private var isMonitoring = false

    // 传感器是否已注册。监测期间即使界面退到后台也不注销，由前台服务保活
    private var isSensorRegistered = false

    companion object {
        private const val LEVEL_TOLERANCE_DEG = 0.5f // 判定为“水平”的角度阈值
        private const val LOW_PASS_ALPHA = 0.15f     // 加速度计低通滤波系数
        private const val SEND_INTERVAL_MS = 16L // 数据发送间隔（约 60Hz，与显示刷新率对齐，不再额外引入延迟）
        private const val RUMBLE_TICK_MS = 80L   // 每次补振的时长，同时也是震动强度的刷新间隔
        private const val RUMBLE_TIMEOUT_MS = 400L // 超过这么久没收到新强度就停振（掉线兜底）
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏：不让系统按系统栏给内容留边，这样内容可以铺满整屏（含挖孔区域）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        setContentView(R.layout.activity_main)
        applyWindowInsets()
        hideSystemBars()

        initSensors()
        setupListeners()
        setupLan()
        updateLocalIp()

        // 监测与局域网两个开关都已去掉：打开软件后直接全开
        startMonitoring()
        startLan()
    }

    /**
     * 全屏之后系统不再帮我们避让，这里自己算内边距：
     * 16dp 基础留白 + 系统栏 / 挖孔的安全区（横屏时挖孔在左右某一侧）
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.rootLayout)
        val base = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(base + safe.left, base + safe.top, base + safe.right, base + safe.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    /** 沉浸式全屏：隐藏状态栏与导航栏，从屏幕边缘上滑可临时唤出 */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 弹窗、Toast、从后台回来之后系统栏会重新冒出来，这里再收一次 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        needLowPassFilter = gravitySensor == null

        if (gravitySensor != null || accelerometerSensor != null) {
            tvSensorStatus.text = "已连接"
            tvSensorStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvSensorStatus.text = "传感器不可用"
            tvSensorStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            Toast.makeText(this, "您的设备不支持重力/加速度传感器", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupListeners() {
        bindTrigger(padLt, tvLtValue)
        bindTrigger(padRt, tvRtValue)
        bindGamepadButton(btnX) { pressed -> isXPressed = pressed }
        bindGamepadButton(btnA) { pressed -> isAPressed = pressed }
    }

    /**
     * X / A 按键：按下与抬起都要立刻上报，所以用触摸事件而不是点击事件
     * （点击事件只有抬手时才触发，手柄按键需要按住期间一直保持按下状态）。
     */
    private fun bindGamepadButton(button: Button, onChanged: (Boolean) -> Unit) {
        var pressed = false
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (!pressed) {
                    pressed = true
                    applyButtonPressed(view as Button, onChanged, true)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pressed) {
                    pressed = false
                    applyButtonPressed(view as Button, onChanged, false)
                }
            }
            // 返回 false 让系统继续走默认点击流程（保留按下时的视觉反馈）
            false
        }
    }

    private fun applyButtonPressed(button: Button, onChanged: (Boolean) -> Unit, pressed: Boolean) {
        onChanged(pressed)

        button.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (pressed) android.R.color.holo_blue_dark else android.R.color.darker_gray
        )
        sendButtonNow()
    }

    /**
     * 按键状态一变就立刻补发一帧，避免 16ms 限流把短促的点按吞掉
     * （此时 lastAngle 就是当前倾角）。
     */
    private fun sendButtonNow() {
        if (!isMonitoring) return
        sendTilt(lastAngle, lastDirection, force = true)
    }

    /** 扳机操控框：刷新数值文本，值一变就立即补发一帧（含松手回弹到 0） */
    private fun bindTrigger(pad: TriggerPadView, label: TextView) {
        pad.onProgressChanged = { value ->
            label.text = value.toString()
            sendTriggerNow()
        }
    }

    /**
     * 手机完全静止时重力传感器可能不再回调，只靠周期性发送会让扳机变化迟迟传不过去，
     * 因此扳机值一变就立刻补发一帧（此时 lastAngle 就是当前倾角）。
     */
    private fun sendTriggerNow() {
        if (!isMonitoring) return
        sendTilt(lastAngle, lastDirection, force = true)
    }

    private fun setupLan() {
        // 状态行只显示两种内容：未连接，或已接入的客户端 IP（由 TiltNetServer 给出）
        netServer.onStatusChanged = { text -> tvLanStatus.text = text }
        // 电脑端把虚拟手柄收到的震动强度回传过来，这里驱动手机振动
        netServer.onRumbleReceived = { large, small -> applyRumble(large, small) }
        tvLanStatus.text = "未连接"
    }

    /**
     * 电脑端回传的震动强度（0~255）：手机只有一个马达，取大小马达里更强的一路。
     * 强度为 0 就直接停振。
     */
    private fun applyRumble(large: Int, small: Int) {
        lastRumbleAt = SystemClock.elapsedRealtime()
        val amplitude = maxOf(large, small).coerceIn(0, 255)
        if (amplitude == 0) {
            stopRumble()
            return
        }

        rumbleAmplitude = amplitude
        if (!rumbleRunning) {
            rumbleRunning = true
            rumbleHandler.post(rumbleTicker)
        }
    }

    /**
     * 震动循环：单次震动只按 [RUMBLE_TICK_MS] 下发，然后自己重排下一次，
     * 这样强度变化最多 [RUMBLE_TICK_MS] 就能生效；停止时不需要额外取消上一次的短震。
     */
    private val rumbleTicker = object : Runnable {
        override fun run() {
            // 电脑端不再下发（游戏停止震动、掉线）时，靠超时兜底停振
            val fresh = SystemClock.elapsedRealtime() - lastRumbleAt <= RUMBLE_TIMEOUT_MS
            if (rumbleAmplitude <= 0 || !fresh) {
                stopRumble()
                return
            }

            val effect = VibrationEffect.createOneShot(RUMBLE_TICK_MS, rumbleAmplitude)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 必须显式声明 MEDIA：默认的 TOUCH 用法会跟随系统的“触感反馈”开关，
                // 用户关掉触感反馈后手柄震动就一起被静音了
                vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                vibrator?.vibrate(effect)
            }
            rumbleHandler.postDelayed(this, RUMBLE_TICK_MS)
        }
    }

    private fun stopRumble() {
        rumbleAmplitude = 0
        rumbleRunning = false
        rumbleHandler.removeCallbacks(rumbleTicker)
        vibrator?.cancel()
    }

    /** 显示本机当前局域网 IP，便于直接填进 Windows 客户端 */
    private fun updateLocalIp() {
        tvLocalIp.text = netServer.localIpAddress()
    }

    private fun startLan() {
        if (!netServer.start()) {
            Toast.makeText(this, "局域网启动失败，请检查端口是否被占用", Toast.LENGTH_SHORT).show()
            return
        }
        isLanOn = true
        updateLocalIp()
        syncKeepAlive()
    }

    /**
     * 监测或局域网任一开启时启动前台保活服务，两者都关闭时停止。
     * 服务运行时进程不会被系统冻结，界面退到后台也能继续发送数据。
     */
    private fun syncKeepAlive() {
        if (isMonitoring || isLanOn) {
            requestNotificationPermissionIfNeeded()
            KeepAliveService.start(this)
        } else {
            KeepAliveService.stop(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (notificationPermissionAsked) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        notificationPermissionAsked = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startMonitoring() {
        val sensor = gravitySensor ?: accelerometerSensor
        if (sensor == null) {
            Toast.makeText(this, "传感器不可用", Toast.LENGTH_SHORT).show()
            return
        }

        // 水平仪需要较高刷新率，使用 SENSOR_DELAY_GAME 以获得更顺滑的气泡移动
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        isSensorRegistered = true

        isMonitoring = true
        tvSensorStatus.text = "监测中"
        syncKeepAlive()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> System.arraycopy(event.values, 0, gravity, 0, 3)
            Sensor.TYPE_ACCELEROMETER -> {
                if (needLowPassFilter) {
                    for (i in gravity.indices) {
                        gravity[i] += LOW_PASS_ALPHA * (event.values[i] - gravity[i])
                    }
                } else {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                }
            }
            else -> return
        }

        updateLevel()
    }

    private fun updateLevel() {
        // 把设备坐标系下的重力向量换算到当前屏幕坐标系。
        // Android 约定：设备逆时针旋转 90° 时为 ROTATION_90，此时屏幕的“上”方向
        // 对应设备 +X 轴、屏幕的“右”方向对应设备 -Y 轴。这样横屏方向翻转
        // （90° <-> 270°）时左右基准会自动跟着切换。
        val screenX: Float
        val screenY: Float
        when (getDisplayRotation()) {
            Surface.ROTATION_90 -> {
                screenX = -gravity[1]
                screenY = gravity[0]
            }
            Surface.ROTATION_180 -> {
                screenX = -gravity[0]
                screenY = -gravity[1]
            }
            Surface.ROTATION_270 -> {
                screenX = gravity[1]
                screenY = -gravity[0]
            }
            else -> {
                screenX = gravity[0]
                screenY = gravity[1]
            }
        }

        // 仅计算横向（左右）倾角，正值为屏幕左侧偏低
        val horizontal = sqrt(screenY * screenY + gravity[2] * gravity[2])
        val angle = Math.toDegrees(atan2(screenX.toDouble(), horizontal.toDouble())).toFloat()

        levelView.update(angle, active = true)

        // 倾角与倾斜方向只用于发送给电脑端（界面上的三行文字已移除）
        lastAngle = angle
        lastDirection = if (abs(angle) <= LEVEL_TOLERANCE_DEG) {
            "水平"
        } else if (angle > 0) {
            "向左倾斜"
        } else {
            "向右倾斜"
        }
        sendTilt(angle, lastDirection)
    }

    /**
     * 把倾斜角、两个扳机值与 X / A 按键状态以 JSON 行发给 Windows 客户端。
     * 按 SEND_INTERVAL_MS 限流，避免传感器满速发送把网络打满；
     * force=true 用于扳机拖动 / 按键变化时的立即补发。
     */
    private fun sendTilt(angle: Float, direction: String, force: Boolean = false) {
        if (!netServer.isReady) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSentAt < SEND_INTERVAL_MS) return
        lastSentAt = now

        val json = JSONObject()
        json.put("angle", round(angle * 10.0) / 10.0)
        json.put("direction", direction)
        json.put("rotation", getDisplayRotation())
        json.put("lt", padLt.progress)
        json.put("rt", padRt.progress)
        json.put("x", if (isXPressed) 1 else 0)
        json.put("a", if (isAPressed) 1 else 0)
        json.put("ts", System.currentTimeMillis())

        netServer.send(json.toString())
    }

    private fun getDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // 水平仪只关心重力方向，无需处理精度变化
    }

    override fun onResume() {
        super.onResume()
        // 网络可能已经变化，刷新一次本机 IP
        updateLocalIp()
        // 传感器在 onPause 时不注销，这里只需处理极端情况下被系统回收注册的场景
        if (isMonitoring && !isSensorRegistered) {
            val sensor = gravitySensor ?: accelerometerSensor
            if (sensor != null) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                isSensorRegistered = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 确保取消注册传感器监听器
        sensorManager.unregisterListener(this)
        isSensorRegistered = false
        // 释放局域网监听与客户端连接
        netServer.onStatusChanged = null
        netServer.onRumbleReceived = null
        netServer.stop()
        stopRumble()
        // 停止前台保活服务
        KeepAliveService.stop(this)
    }
}