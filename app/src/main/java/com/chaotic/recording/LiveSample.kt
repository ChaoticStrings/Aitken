package com.aitken.recording

/**
 * One sample's worth of what the live graph draws.
 *
 * @property vertical vertical acceleration, m/s^2 (rests near 9.81).
 * @property roughness sliding-window mean of deviation from gravity (the D reading).
 * @property segmentOpen a segment is open (being recorded) at this sample.
 * @property clipped an accelerometer axis is saturated at this sample.
 */
data class LiveSample(
    val vertical: Float,
    val roughness: Float,
    val segmentOpen: Boolean,
    val clipped: Boolean
)
