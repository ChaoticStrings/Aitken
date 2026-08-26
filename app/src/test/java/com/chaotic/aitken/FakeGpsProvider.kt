package com.aitken.location

/**
 * Test double for GpsProvider. Fixes are pushed one at a time via emitFix(),
 * simulating a real fix stream with no Android dependency.
 */
class FakeGpsProvider : GpsProvider {

    private var callback: ((GpsFix) -> Unit)? = null
    private var latest: GpsFix? = null

    var startCallCount = 0
        private set
    var stopCallCount = 0
        private set

    /** Script start() to fail, e.g. to simulate no location permission. */
    var startResult = true

    override fun currentFix(): GpsFix? = latest

    override fun start(onFix: (GpsFix) -> Unit): Boolean {
        startCallCount++
        if (!startResult) return false
        callback = onFix
        return true
    }

    override fun stop() {
        stopCallCount++
        callback = null
    }

    /** Test helper: push a scripted fix through the stream. */
    fun emitFix(fix: GpsFix) {
        latest = fix
        callback?.invoke(fix)
    }
}
