package com.studyorbit.app

/** A sorted block of ASCII domains, with a byte offset for each newline-terminated entry. */
internal class DomainBlock(private val bytes: ByteArray) {
    private val starts = IntArray(bytes.count { it == NEWLINE } + 1).also { offsets ->
        var line = 1
        for (offset in bytes.indices) {
            if (bytes[offset] == NEWLINE) offsets[line++] = offset + 1
        }
    }

    operator fun contains(host: String): Boolean {
        var low = 0
        var high = starts.size - 2
        while (low <= high) {
            val middle = (low + high) ushr 1
            val comparison = compareLine(middle, host)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return true
            }
        }
        return false
    }

    private fun compareLine(line: Int, host: String): Int {
        val start = starts[line]
        val length = starts[line + 1] - start - 1
        for (index in 0 until minOf(length, host.length)) {
            val difference = bytes[start + index].toInt() - host[index].code
            if (difference != 0) return difference
        }
        return length - host.length
    }

    private companion object {
        const val NEWLINE: Byte = 10
    }
}
