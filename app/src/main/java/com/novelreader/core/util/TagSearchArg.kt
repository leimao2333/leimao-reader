package com.novelreader.core.util

import java.net.URLDecoder
import java.util.Base64

/** Navigation-safe codec for tag-search query arguments. */
object TagSearchArg {
    private const val PREFIX = "tag64_"

    fun encode(tag: String): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(tag.toByteArray(Charsets.UTF_8))

    fun decode(argument: String): String {
        if (argument.startsWith(PREFIX)) {
            val encoded = argument.removePrefix(PREFIX)
            return runCatching {
                String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            }.getOrDefault(argument)
        }
        return runCatching {
            URLDecoder.decode(argument, Charsets.UTF_8.name())
        }.getOrDefault(argument)
    }
}
