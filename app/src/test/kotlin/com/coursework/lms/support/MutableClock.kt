package com.coursework.lms.support

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The application clock in tests: real time until a test moves it forward. */
@Primary
@Component
class MutableClock : Clock() {

    @Volatile
    private var offset: Duration = Duration.ZERO

    fun advance(duration: Duration) {
        offset += duration
    }

    fun reset() {
        offset = Duration.ZERO
    }

    override fun instant(): Instant {
        return Instant.now().plus(offset)
    }

    override fun getZone(): ZoneId {
        return ZoneOffset.UTC
    }

    override fun withZone(zone: ZoneId): Clock {
        return this
    }
}
