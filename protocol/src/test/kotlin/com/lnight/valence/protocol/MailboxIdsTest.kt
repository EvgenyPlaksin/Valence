package com.lnight.valence.protocol

import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MailboxIdsTest {
    @Test
    fun `a mailbox id maps to its uuid form`() {
        assertEquals("3723a57e-fd83-2d5f-05b9-18a5b6f1e4e8", MailboxIds.toUuid("mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a"))
    }

    @Test
    fun `the extreme ids map to the extreme uuids`() {
        assertEquals("00000000-0000-0000-0000-000000000000", MailboxIds.toUuid("mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertEquals("ffffffff-ffff-ffff-ffff-ffffffffffff", MailboxIds.toUuid("mbx_77777777777777777777777774"))
    }

    @Test
    fun `the mapping round trips in both directions`() {
        assertEquals("mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a", MailboxIds.fromUuid("3723a57e-fd83-2d5f-05b9-18a5b6f1e4e8"))
        val random = Random(1)
        repeat(200) {
            val uuid = UUID(random.nextLong(), random.nextLong()).toString()
            val mailboxId = requireNotNull(MailboxIds.fromUuid(uuid))
            assertEquals(uuid, MailboxIds.toUuid(mailboxId))
        }
    }

    @Test
    fun `ids that are not canonical are rejected`() {
        listOf(
            "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5b",
            "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5",
            "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5aa",
            "xyz_g4r2k7x5qmwv6bnzdcs3n4pe5a",
            "mbx_g4r2k7x5qmwv6bnzdcs3n4pe51",
            "MBX_G4R2K7X5QMWV6BNZDCS3N4PE5A",
            "",
        ).forEach { assertNull(MailboxIds.toUuid(it), it) }
    }

    @Test
    fun `uuids that are not canonical are rejected`() {
        listOf("3723A57E-FD83-2D5F-05B9-18A5B6F1E4E8", "1-1-1-1-1", "garbage", "").forEach {
            assertNull(MailboxIds.fromUuid(it), it)
        }
    }
}
