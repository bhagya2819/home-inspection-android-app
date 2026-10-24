package com.homeinspection.app.core.storage

import com.homeinspection.app.core.storage.StoragePolicy.SYSTEM_RESERVE_BYTES
import com.homeinspection.app.core.storage.StoragePolicy.TYPICAL_PHOTO_BYTES
import com.homeinspection.app.core.storage.StoragePolicy.LOW_WARNING_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {
    private val fullLimit = SYSTEM_RESERVE_BYTES + TYPICAL_PHOTO_BYTES

    @Test fun plentyOfSpaceIsOk() = assertEquals(StorageCheck.Ok, StoragePolicy.check(70L * 1024 * 1024 * 1024))

    @Test fun exactlyAtWarningLimitIsOk() = assertEquals(StorageCheck.Ok, StoragePolicy.check(LOW_WARNING_BYTES))

    @Test fun justBelowWarningLimitIsLow() =
        assertTrue(StoragePolicy.check(LOW_WARNING_BYTES - 1) is StorageCheck.Low)

    @Test fun exactlyAtFullLimitStillAllowsCapture() =
        assertTrue(StoragePolicy.check(fullLimit) is StorageCheck.Low)

    @Test fun belowFullLimitBlocksCapture() =
        assertTrue(StoragePolicy.check(fullLimit - 1) is StorageCheck.Full)

    @Test fun zeroOrNegativeBlocksCapture() {
        assertTrue(StoragePolicy.check(0) is StorageCheck.Full)
        assertTrue(StoragePolicy.check(-1) is StorageCheck.Full)
    }
}
