package com.lnight.valence.core.database

import com.lnight.valence.core.model.Contact
import com.lnight.valence.core.model.ContactId
import com.lnight.valence.core.model.MailboxId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactEntityMapperTest {
    private val mapper = ContactEntityMapper()

    @Test
    fun mapsToDomainAndEntityCorrectly() {
        val contactId = ContactId("contact-123")
        val mailboxId = MailboxId("mailbox-456")
        val domain =
            Contact(
                id = contactId,
                mailboxId = mailboxId,
                displayName = "Alice",
                createdAtMillis = 1000L,
            )
        val keys =
            ContactKeyStorage(
                contactId = "contact-123",
                identityKey = ImmutableBytes(byteArrayOf(1, 2, 3)),
                deliveryKey = ImmutableBytes(byteArrayOf(4, 5, 6)),
                safetyNumberHash = ImmutableBytes(byteArrayOf(7, 8)),
            )

        val entity = mapper.toEntity(domain, keys, trustState = 1)

        assertEquals("contact-123", entity.contactId)
        assertEquals("mailbox-456", entity.mailboxId)
        assertEquals("Alice", entity.displayName)
        assertArrayEquals(byteArrayOf(1, 2, 3), entity.identityKey)
        assertArrayEquals(byteArrayOf(4, 5, 6), entity.deliveryKey)
        assertEquals(1, entity.trustState)
        assertArrayEquals(byteArrayOf(7, 8), entity.safetyNumberHash)
        assertEquals(1000L, entity.createdAtMillis)

        val mappedDomain = mapper.toDomain(entity)
        assertEquals(domain, mappedDomain)
    }

    @Test
    fun mapsEntityWithNullSafetyNumberHash() {
        val domain =
            Contact(
                id = ContactId("contact-123"),
                mailboxId = MailboxId("mailbox-456"),
                displayName = "Alice",
                createdAtMillis = 1000L,
            )
        val keys =
            ContactKeyStorage(
                contactId = "contact-123",
                identityKey = ImmutableBytes(byteArrayOf(1)),
                deliveryKey = ImmutableBytes(byteArrayOf(2)),
                safetyNumberHash = null,
            )

        val entity = mapper.toEntity(domain, keys)
        assertNull(entity.safetyNumberHash)

        val mappedDomain = mapper.toDomain(entity)
        assertEquals(domain, mappedDomain)
    }
}
