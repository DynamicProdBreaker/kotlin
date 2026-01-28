package org.jetbrains.benchmarksLauncher

import kotlin.native.concurrent.ThreadLocal

class Blackhole {
    @ThreadLocal
    companion object {
        var consumer = 0
        fun consume(value: Any) {
            consumer += value.hashCode()
        }
    }
}

class Random constructor() {
    @ThreadLocal
    companion object {
        var seedInt = 0
        fun nextInt(boundary: Int): Int {
            seedInt = (3 * seedInt + 11) % boundary
            return seedInt
        }

        var seedDouble: Double = 0.1
        fun nextDouble(boundary: Double): Double {
            seedDouble = (7.0 * seedDouble + 7.0) % boundary
            return seedDouble
        }
    }
}