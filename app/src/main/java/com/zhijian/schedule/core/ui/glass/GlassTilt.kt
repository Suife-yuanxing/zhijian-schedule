package com.zhijian.schedule.core.ui.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 倾角信号（竖持参考系下归一化到 [-1,1]）：
 * x = 左右倾（右倾为正）；y = 前后倾（屏朝天天平放为 +1，竖持 ≈ 0，屏朝地为 -1）。
 * 重力方向低通滤噪（保留缓缓滑动的倾向），只在 RESUMED 注册监听、离屏即摘。
 * 仅玻璃主题下由 AppRoot 持有；绘制期读取（不触发组合重组），其余主题零开销。
 */
@Composable
fun rememberGlassTilt(): State<Offset> {
    val tilt = remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val gravity =
            sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var gx = 0f
        var gz = 0f
        var registered = false
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    gx = gx * 0.82f + event.values[0] * 0.18f
                    gz = gz * 0.82f + event.values[2] * 0.18f
                    tilt.value =
                        Offset(
                            (gx / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f),
                            (gz / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f),
                        )
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }

        fun register() {
            if (!registered && sensorManager != null && gravity != null) {
                sensorManager.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_GAME)
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                sensorManager?.unregisterListener(listener)
                registered = false
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> register()
                    Lifecycle.Event.ON_PAUSE -> unregister()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
    return tilt
}
