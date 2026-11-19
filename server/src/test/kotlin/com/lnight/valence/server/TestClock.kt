package com.lnight.valence.server

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal class TestClock(
    startMillis: Long,
) : Clock() {
    @Volatile
    private var millis = startMillis

    fun advance(byMillis: Long) {
        millis += byMillis
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(millis)

    override fun millis(): Long = millis
}
