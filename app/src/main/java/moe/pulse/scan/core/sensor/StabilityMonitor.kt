package moe.pulse.scan.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Gyro-stabilization gate, matching the legacy threshold: the device is
 * considered stable when the sum of absolute angular velocities on all three
 * axes stays below 0.3 rad/s.
 */
class StabilityMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _isStable = MutableStateFlow(true)
    val isStable: StateFlow<Boolean> = _isStable

    fun start() {
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val velocity = event.values[0].let { Math.abs(it) } +
            event.values[1].let { Math.abs(it) } +
            event.values[2].let { Math.abs(it) }
        val stable = velocity < 0.3
        if (stable != _isStable.value) {
            _isStable.value = stable
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
