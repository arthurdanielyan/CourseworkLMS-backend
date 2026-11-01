package com.coursework.lms.core.scheduling

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// Turns on @Scheduled for every module; jobs themselves live with the capability that owns their data.
@Configuration
@EnableScheduling
class SchedulingConfig
