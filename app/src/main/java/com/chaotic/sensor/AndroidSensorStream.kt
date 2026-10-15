package com.aitken.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Production SensorStream backed by SensorManager.
 *
 * Accelerometer and gyroscope are requested at 100 Hz with a batching latency
 * hint of 50 ms. The gyro is optional on the target device.
 */
class AndroidSensorStream(context: Context) : SensorStream {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gyroscope =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val handlerThread = HandlerThread("crater-sensor-stream").apply {
        start()
    }

    private val handler = Handler(handlerThread.looper)

    @Volatile
    private var latestGyro: FloatArray? = null

    @Volatile
    private var callback: ((SensorStream.SensorSample) -> Unit)? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val cb = callback ?: return
                    val gyro = latestGyro
                    cb(
                        SensorStream.SensorSample(
                            timestampNs = event.timestamp,
                            accelX = event.values[0],
                            accelY = event.values[1],
                            accelZ = event.values[2],
                            gyroX = gyro?.getOrNull(0),
                            gyroY = gyro?.getOrNull(1),
                            gyroZ = gyro?.getOrNull(2)
                        )
                    )
                }

                Sensor.TYPE_GYROSCOPE -> {
                    latestGyro = event.values.clone()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // No action needed for Slice 01.
        }
    }

    override fun start(onSample: (SensorStream.SensorSample) -> Unit): Boolean {
        val accel = accelerometer ?: return false
        callback = onSample

        // 100 Hz = 10,000 us; batching latency hint 50 ms = 50,000 us.
        val samplingPeriodUs = 10_000
        val maxReportLatencyUs = 50_000

        val accelOk = sensorManager.registerListener(
            listener,
            accel,
            samplingPeriodUs,
            maxReportLatencyUs,
            handler
        )

        val gyro = gyroscope
        if (gyro != null) {
            sensorManager.registerListener(
                listener,
                gyro,
                samplingPeriodUs,
                maxReportLatencyUs,
                handler
            )
        }

        return accelOk
    }

    override fun stop() {
        callback = null
        sensorManager.unregisterListener(listener)
    }

    override fun close() {
        stop()
        handlerThread.quitSafely()
    }

    companion object {
        /**
         * Reads the default accelerometer's name, vendor and saturation limit
         * (`Sensor.getMaximumRange()`, m/s^2) without registering a listener —
         * safe to call from the idle screen as well as from a session.
         * Null if the device has no accelerometer.
         */
        fun queryAccelerometerInfo(context: Context): AccelerometerInfo? {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val s = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
            return AccelerometerInfo(
                name = s.name ?: "",
                vendor = s.vendor ?: "",
                maxRangeMs2 = s.maximumRange,
                resolutionMs2 = s.resolution
            )
        }
    }
}
