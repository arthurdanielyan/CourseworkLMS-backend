package com.coursework.lms

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LmsApplication

fun main(args: Array<String>) {
    runApplication<LmsApplication>(*args)
}
