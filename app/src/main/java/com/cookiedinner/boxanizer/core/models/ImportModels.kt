package com.cookiedinner.boxanizer.core.models

import java.io.File

data class DataCounts(
    val boxes: Long,
    val items: Long,
    val boxItems: Long,
    val tags: Long,
    val photos: Long
)

/**
 * A database file that passed validation and is waiting for the user's confirmation.
 */
data class ImportCandidate(
    val file: File,
    val fileCounts: DataCounts,
    val currentCounts: DataCounts
)

class ImportException(val reason: Reason, message: String? = null) : Exception(message ?: reason.name) {
    enum class Reason {
        NOT_A_DATABASE,
        CORRUPTED,
        NEWER_VERSION,
        NOT_ENOUGH_SPACE,
        FAILED
    }
}
