package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.mapper

object AppUtils {

    inline fun <reified T> parseJson(jsonString: String): T =
        mapper.readValue(jsonString, object : TypeReference<T>() {})
}
