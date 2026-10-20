package com.lnight.valence.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolVersionTest {
    @Test
    fun `only supported versions are accepted`() {
        assertTrue(ProtocolVersion.isSupported(1))
        assertFalse(ProtocolVersion.isSupported(0))
        assertFalse(ProtocolVersion.isSupported(2))
    }
}
