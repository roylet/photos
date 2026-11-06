package com.roylet.photos.cast

/** What a `Range` header asks of a [length]-byte body (RFC 9110 §14). */
sealed interface RangeRequest {
    /** No (usable) Range header: 200 with the whole body. */
    data object Full : RangeRequest
    /** 206 with bytes [start]..[end] inclusive. */
    data class Partial(val start: Long, val end: Long) : RangeRequest {
        val length: Long get() = end - start + 1
        fun contentRange(total: Long) = "bytes $start-$end/$total"
    }
    /** 416, with `Content-Range: bytes * /length`. */
    data object Unsatisfiable : RangeRequest

    companion object {
        /**
         * Parses `bytes=a-b`, `bytes=a-` and the suffix form `bytes=-n`. Only the first range of a
         * multi-range request is served (receivers only ever ask for one). Anything malformed, or
         * not in bytes, is ignored (the whole body), as the RFC allows.
         */
        fun parse(header: String?, length: Long): RangeRequest {
            val h = header?.trim() ?: return Full
            if (!h.startsWith("bytes=", ignoreCase = true)) return Full
            val spec = h.substring(6).substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return Full
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            if (a.isEmpty()) {
                // Suffix: the last n bytes.
                val n = b.toLongOrNull() ?: return Full
                if (n <= 0 || length == 0L) return Unsatisfiable
                return Partial((length - n).coerceAtLeast(0), length - 1)
            }
            val start = a.toLongOrNull() ?: return Full
            val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return Full)
            if (start < 0) return Full
            if (start >= length) return Unsatisfiable
            if (end < start) return Full
            return Partial(start, end.coerceAtMost(length - 1))
        }
    }
}
