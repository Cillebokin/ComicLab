package com.example.comiclab.core.store

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrefsGatewayRobolectricTest {

    @Test
    fun openUsesTheRequestedPrivatePreferencesFileWithoutChangingLegacyValues() {
        val context = RuntimeEnvironment.getApplication()
        val preferencesName = "prefs_gateway_compatibility_test"
        val otherPreferencesName = "prefs_gateway_isolation_test"
        val existingPreferences = context.getSharedPreferences(
            preferencesName,
            Context.MODE_PRIVATE
        )
        existingPreferences.edit()
            .clear()
            .putString("legacy_key", "legacy_value")
            .commit()

        try {
            val gatewayPreferences = PrefsGateway.open(context, preferencesName)
            val otherPreferences = PrefsGateway.open(context, otherPreferencesName)

            assertSame(existingPreferences, gatewayPreferences)
            assertEquals("legacy_value", gatewayPreferences.getString("legacy_key", null))
            assertNull(otherPreferences.getString("legacy_key", null))
        } finally {
            existingPreferences.edit().clear().commit()
            context.getSharedPreferences(otherPreferencesName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }
}
