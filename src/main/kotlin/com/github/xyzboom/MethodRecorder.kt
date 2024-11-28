package com.github.xyzboom

import io.github.oshai.kotlinlogging.KotlinLogging

object MethodRecorder {
    private val logger = KotlinLogging.logger {}
    private val visited = mutableSetOf<String>()

    @JvmStatic
    fun record(name: String) {
        if (name in visited) {
            return
        }
        visited.add(name)
        logger.info { name }
    }
}