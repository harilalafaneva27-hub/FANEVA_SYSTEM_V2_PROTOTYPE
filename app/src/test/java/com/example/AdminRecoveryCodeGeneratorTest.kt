package com.example

import com.example.data.security.AdminRecoveryCodeGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdminRecoveryCodeGeneratorTest {
    @Test
    fun generatedCodeIsHighEntropyAndOnlyItsVerifierMaterialIsExposedForStorage() {
        val first = AdminRecoveryCodeGenerator.generate()
        val second = AdminRecoveryCodeGenerator.generate()

        assertTrue(first.plaintext.length >= 64)
        assertNotEquals(first.plaintext, second.plaintext)
        assertTrue(first.salt.isNotBlank())
        assertTrue(first.hash.isNotBlank())
        assertFalse(first.hash.contains(first.plaintext))
        assertTrue(
            AdminRecoveryCodeGenerator.verify(
                first.plaintext.toCharArray(),
                first.salt,
                first.hash
            )
        )
        assertFalse(
            AdminRecoveryCodeGenerator.verify(
                second.plaintext.toCharArray(),
                first.salt,
                first.hash
            )
        )
    }
}
