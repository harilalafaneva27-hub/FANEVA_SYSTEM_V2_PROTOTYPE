package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.security.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceIdentityTest {
    @Test
    fun `installation id remains stable across calls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val first = DeviceIdentity.getOrCreate(context)
        val second = DeviceIdentity.getOrCreate(context)

        assertEquals(first, second)
        assertTrue(runCatching { UUID.fromString(first) }.isSuccess)
    }

    @Test
    fun `installation id is persisted in app private preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val generated = DeviceIdentity.getOrCreate(context)
        val stored = context.getSharedPreferences(
            "faneva_installation",
            Context.MODE_PRIVATE
        ).getString("device_id", null)

        assertEquals(generated, stored)
    }

    @Test
    fun `invalid stored installation id is replaced with a valid uuid`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(
            "faneva_installation",
            Context.MODE_PRIVATE
        )
        preferences.edit().putString("device_id", "invalid-device-id").commit()

        val generated = DeviceIdentity.getOrCreate(context)
        val stored = preferences.getString("device_id", null)

        assertTrue(runCatching { UUID.fromString(generated) }.isSuccess)
        assertEquals(generated, stored)
        assertNotEquals("invalid-device-id", generated)
    }

    @Test
    fun `clearing app preferences creates a new installation id`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = DeviceIdentity.getOrCreate(context)

        context.getSharedPreferences(
            "faneva_installation",
            Context.MODE_PRIVATE
        ).edit().clear().commit()

        val second = DeviceIdentity.getOrCreate(context)
        assertNotEquals(first, second)
    }
}
