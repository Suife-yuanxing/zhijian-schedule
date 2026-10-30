package com.zhijian.schedule.core.ui.metal

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
 * 倾角信号（归一化 [-1,1]）：x = 左右倾（右倾为正），y = 前后倾（屏朝上为 +1）。
 *
 * 对金属来说这个量比玻璃更关键：玻璃的折射方向由**几何形状**决定（永远朝内），
 * 而金属的反射方向由**观察者与光源的相对角度**决定——手机一转，整块金属上的高光会长距离滑过表面。
 * 所以这里用 GAME 级采样 + 较轻的低通（0.86/0.14，比玻璃的 0.82/0.18 更钝），
 * 让高光滑动是「缓缓地滑」而不是「抖」。
 *
 * 只在 RESUMED 注册监听，离屏即摘，其余主题完全不创建。
 */
@Composable
fun rememberMetalGyro(): State<Offset> {
    val gyro = remember { mutableStateOf(Offset.Zero) }
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
                    gx = gx * 0.86f + event.values[0] * 0.14f
                    gz = gz * 0.86f + event.values[2] * 0.14f
                    gyro.value =
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
    return gyro
}
