package com.lnight.valence.server

import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.store.InMemoryMailboxStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeliveryKeyAuthenticatorTest {
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val store = InMemoryMailboxStore().also { it.create(DeliveryKeyTestSupport.mailbox(), emptyList()) }
    private val authenticator = DeliveryKeyAuthenticator(store)

    @Test
    fun `the right key authenticates the mailbox`() {
        assertEquals(id, authenticator.authenticate(id, DeliveryKeyTestSupport.deliveryKey)?.id)
    }

    @Test
    fun `a wrong or empty key does not authenticate`() {
        assertNull(authenticator.authenticate(id, ByteArray(16) { 4 }))
        assertNull(authenticator.authenticate(id, ByteArray(0)))
    }

    @Test
    fun `an unknown mailbox does not authenticate`() {
        assertNull(authenticator.authenticate("mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa", DeliveryKeyTestSupport.deliveryKey))
        assertNull(authenticator.authenticate("nonsense", DeliveryKeyTestSupport.deliveryKey))
    }
}
