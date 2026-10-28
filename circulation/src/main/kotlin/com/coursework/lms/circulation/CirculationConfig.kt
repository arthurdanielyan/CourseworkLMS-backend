package com.coursework.lms.circulation

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.ZoneId

@Configuration
@EnableConfigurationProperties(CirculationProperties::class)
class CirculationConfig

/** Bound from `lms.circulation.*`. */
@ConfigurationProperties("lms.circulation")
data class CirculationProperties(
    val pickupWindow: Duration = Duration.ofHours(48),
)

/** The library's time zone, which decides what "today" is for return dates and Overdue. */
// The reservation trigger in V7 uses the same zone; change both together.
val LIBRARY_ZONE: ZoneId = ZoneId.of("Asia/Yerevan")
