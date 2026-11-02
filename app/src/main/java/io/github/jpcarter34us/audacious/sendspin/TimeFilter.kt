package io.github.jpcarter34us.audacious.sendspin

/** The client clock Sendspin timestamps are mapped onto, in microseconds.
 *  Monotonic, shared by the protocol (time sync) and the playback engine
 *  (when to write a chunk). */
object SendspinClock {
    fun nowMicros(): Long = System.nanoTime() / 1000
}

/**
 * Two-dimensional Kalman filter (offset and drift) over NTP-style
 * measurements, ported from the iOS port's SendspinTime.swift
 * (sendspin-js `time-filter.ts`, itself a port of aiosendspin's).
 */
class SendspinTimeFilter(
    offsetProcessStdDev: Double = 0.0,
    forgetFactor: Double = 1.1,
    driftSignificance: Double = 2.0,
    driftProcessStdDev: Double = 1e-12,
) {
    private var lastUpdate = 0.0
    private var count = 0
    private var offset = 0.0
    private var drift = 0.0
    private var offsetCovariance = Double.POSITIVE_INFINITY
    private var offsetDriftCovariance = 0.0
    private var driftCovariance = 0.0
    private var useDrift = false
    private val offsetProcessVariance = offsetProcessStdDev * offsetProcessStdDev
    private val driftProcessVariance = driftProcessStdDev * driftProcessStdDev
    private val forgetVarianceFactor = forgetFactor * forgetFactor
    private val driftSignificanceSquared = driftSignificance * driftSignificance

    // The snapshot conversions use (read from the decoding thread).
    @Volatile private var current = doubleArrayOf(0.0, 0.0, 0.0)

    private companion object {
        const val ADAPTIVE_FORGETTING_CUTOFF = 2.0
    }

    val isSynchronized: Boolean @Synchronized get() = count >= 1 && !offsetCovariance.isInfinite()
    val error: Double @Synchronized get() = Math.sqrt(offsetCovariance)

    /** `measurement` and `maxError` in microseconds, `timeAdded` on the client clock. */
    @Synchronized
    fun update(measurement: Double, maxError: Double, timeAdded: Double) {
        if (timeAdded <= lastUpdate) return
        val dt = timeAdded - lastUpdate
        lastUpdate = timeAdded
        val variance = maxError * maxError

        if (count <= 0) {
            count++
            offset = measurement
            offsetCovariance = variance
            drift = 0.0
            snapshot(false)
            return
        }
        if (count == 1) {
            count++
            drift = (measurement - offset) / dt
            offset = measurement
            driftCovariance = (offsetCovariance + variance) / (dt * dt)
            offsetCovariance = variance
            snapshot(false)
            return
        }

        // Predict.
        val predicted = offset + drift * dt
        var newDriftCov = driftCovariance + dt * driftProcessVariance
        var newOffsetDriftCov = offsetDriftCovariance + driftCovariance * dt
        var newOffsetCov = offsetCovariance + 2 * offsetDriftCovariance * dt + driftCovariance * dt * dt + dt * offsetProcessVariance

        // Innovation, with adaptive forgetting once mature.
        val residual = measurement - predicted
        if (count < 100) {
            count++
        } else if (Math.abs(residual) > maxError * ADAPTIVE_FORGETTING_CUTOFF) {
            newDriftCov *= forgetVarianceFactor
            newOffsetDriftCov *= forgetVarianceFactor
            newOffsetCov *= forgetVarianceFactor
        }

        // Update.
        val uncertainty = 1 / (newOffsetCov + variance)
        val offsetGain = newOffsetCov * uncertainty
        val driftGain = newOffsetDriftCov * uncertainty
        offset = predicted + offsetGain * residual
        drift += driftGain * residual
        driftCovariance = newDriftCov - driftGain * newOffsetDriftCov
        offsetDriftCovariance = newOffsetDriftCov - driftGain * newOffsetCov
        offsetCovariance = newOffsetCov - offsetGain * newOffsetCov
        snapshot(drift * drift > driftSignificanceSquared * driftCovariance)
    }

    private fun snapshot(drifting: Boolean) {
        useDrift = drifting
        current = doubleArrayOf(lastUpdate, offset, if (drifting) drift else 0.0)
    }

    /** Half away from zero, as Swift's rounded() and JS Math.round on these magnitudes. */
    private fun round(x: Double) = if (x < 0) -Math.floor(-x + 0.5) else Math.floor(x + 0.5)

    fun serverTime(client: Double): Double {
        val c = current
        return client + round(c[1] + c[2] * (client - c[0]))
    }

    fun clientTime(server: Double): Double {
        val c = current
        return round((server - c[1] + c[2] * c[0]) / (1 + c[2]))
    }

    @Synchronized
    fun reset() {
        count = 0
        lastUpdate = 0.0
        offset = 0.0
        drift = 0.0
        offsetCovariance = Double.POSITIVE_INFINITY
        offsetDriftCovariance = 0.0
        driftCovariance = 0.0
        snapshot(false)
    }
}
