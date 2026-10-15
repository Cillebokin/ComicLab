package com.example.comiclab.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseVersionComparatorTest {

    @Test
    fun runtimeVersionMatchesBaseReleaseTag() {
        assertEquals(
            ReleaseVersionComparator.Result.UP_TO_DATE,
            ReleaseVersionComparator.compare("V1.0.9.1006", "V1.0.9.1006_Base")
        )
    }

    @Test
    fun newerRemoteVersionIsComparedNumerically() {
        assertEquals(
            ReleaseVersionComparator.Result.UPDATE_AVAILABLE,
            ReleaseVersionComparator.compare("V1.0.9.1006", "V1.0.10.1001")
        )
    }

    @Test
    fun newerLocalVersionIsNotReportedAsAnAvailableUpdate() {
        assertEquals(
            ReleaseVersionComparator.Result.LOCAL_NEWER,
            ReleaseVersionComparator.compare("V1.1.0.1001", "V1.0.9.1006_Base")
        )
    }

    @Test
    fun unsupportedVersionTagCannotBeCompared() {
        assertEquals(
            ReleaseVersionComparator.Result.UNCOMPARABLE,
            ReleaseVersionComparator.compare("V1.0.9.1006", "nightly-build")
        )
    }
}
