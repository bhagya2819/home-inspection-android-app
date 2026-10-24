package com.homeinspection.app.core.storage

/** Result of the pre-capture storage check, shown to the inspector on the camera screen. */
sealed interface StorageCheck {
    data object Ok : StorageCheck
    /** Capture allowed, but the inspector should be nudged to sync or free space soon. */
    data class Low(val freeBytes: Long) : StorageCheck
    /** Capture refused: we could not guarantee the photo would be fully written. */
    data class Full(val freeBytes: Long) : StorageCheck
}

object StoragePolicy {
    private const val MB = 1024L * 1024

    /** A full-quality JPEG from a modern 50 MP sensor is typically 5–15 MB. */
    const val TYPICAL_PHOTO_BYTES = 12L * MB

    /**
     * Space we never use. Below roughly this, Android itself starts failing (database writes,
     * app updates, the camera HAL's own buffers), so a photo "saved" there is not trustworthy.
     */
    const val SYSTEM_RESERVE_BYTES = 500L * MB

    /** About 125 more photos — roughly two full inspections — before the warning appears. */
    const val LOW_WARNING_BYTES = 2048L * MB

    /**
     * Decides whether the camera may take another photo given current free space (PRD §5.5:
     * "warn about low storage and handle insufficient space without falsely confirming success").
     *
     * Called before every shutter press, so it must be cheap and pure.
     */
    fun check(freeBytes: Long): StorageCheck = when {
        freeBytes < SYSTEM_RESERVE_BYTES + TYPICAL_PHOTO_BYTES -> StorageCheck.Full(freeBytes)
        freeBytes < LOW_WARNING_BYTES -> StorageCheck.Low(freeBytes)
        else -> StorageCheck.Ok
    }
}
