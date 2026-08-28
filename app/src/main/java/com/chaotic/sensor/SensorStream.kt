package com.aitken.sensor

/**
 * Raw sensor stream seam.
 *
 * Implementation is Android-specific (SensorManager); the rest of the
 * pipeline depends only on this interface and SensorSample.
 */
interface SensorStream : AutoCloseable {

    /**
     * Begin streaming samples.
     *
     * @param onSample called once per accelerometer sample, with the latest
     * gyroscope values attached (nullable when gyro is unavailable or not yet
     * delivered).
     * @return true if the accelerometer was registered successfully.
     */
    fun start(onSample: (SensorSample) -> Unit): Boolean

    /** Stop streaming and release sensor registrations. */
    fun stop()

    data class SensorSample(
        val timestampNs: Long,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val gyroX: Float?,
        val gyroY: Float?,
        val gyroZ: Float?
    ) {
        val accelArray: FloatArray
            get() = floatArrayOf(accelX, accelY, accelZ)
    }

    override fun close() {
        stop()
    }
}
