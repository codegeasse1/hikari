package com.lagradost.cloudstream3.utils

enum class Qualities(var value: Int, val defaultPriority: Int) {
    Unknown(-1, 0),
    P144(144, 0),
    P240(240, 1),
    P360(360, 2),
    P480(480, 3),
    P720(720, 4),
    P1080(1080, 5),
    P1440(1440, 6),
    P2160(2160, 7),
}
