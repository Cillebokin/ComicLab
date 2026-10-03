package com.example.comiclab

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ActivityScreenOrientationTest {

    @Test
    fun debugVariantUsesBaseApplicationIdForAndroidStudioLaunch() {
        assertEquals(
            "com.example.comiclab",
            RuntimeEnvironment.getApplication().packageName
        )
    }

    @Test
    fun onlyReaderActivitiesFollowDeviceOrientation() {
        val context = RuntimeEnvironment.getApplication()
        val readerActivities = setOf(
            MangaReaderActivity::class.java.name,
            EbookReaderActivity::class.java.name
        )
        val activities = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            .activities
            .orEmpty()

        assertTrue("The application manifest should declare activities", activities.isNotEmpty())
        activities.forEach { activityInfo ->
            val expectedOrientation = if (activityInfo.name in readerActivities) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            assertEquals(
                "Unexpected screen orientation for ${activityInfo.name}",
                expectedOrientation,
                activityInfo.screenOrientation
            )
        }
    }
}
