package com.lnight.valence.server

import com.lnight.valence.server.domain.MailboxIdFormat
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailboxIdFormatTest {
    @Test
    fun `canonical ids are valid`() {
        listOf("mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a", "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa", "mbx_77777777777777777777777774")
            .forEach { assertTrue(MailboxIdFormat.isValid(it), it) }
    }

    @Test
    fun `ids whose last character carries padding bits are invalid`() {
        listOf("mbx_g4r2k7x5qmwv6bnzdcs3n4pe5b", "mbx_aaaaaaaaaaaaaaaaaaaaaaaaab", "mbx_77777777777777777777777777")
            .forEach { assertFalse(MailboxIdFormat.isValid(it), it) }
    }

    @Test
    fun `other malformed ids are invalid`() {
        listOf("", "mbx_short", "xyz_g4r2k7x5qmwv6bnzdcs3n4pe5a", "MBX_G4R2K7X5QMWV6BNZDCS3N4PE5A")
            .forEach { assertFalse(MailboxIdFormat.isValid(it), it) }
    }
}
