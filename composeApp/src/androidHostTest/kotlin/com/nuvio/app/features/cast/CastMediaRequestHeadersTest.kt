package com.nuvio.app.features.cast

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

class CastMediaRequestHeadersTest {

    @Test
    fun theRequestTypeHasNoHeaderField() {
        val fields = CastMediaRequest::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }

        assertTrue(
            fields.none { it.contains("header", ignoreCase = true) },
            "Cast Media Requests must not carry request headers, found $fields",
        )
    }
}
