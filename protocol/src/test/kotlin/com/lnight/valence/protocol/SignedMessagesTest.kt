package com.lnight.valence.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SignedMessagesTest {
    private val vectors = ProtocolJson.json.parseToJsonElement(GoldenFixtures.read("signed-messages")).jsonObject

    @Test
    fun `registration message matches the golden vector`() {
        val message = registrationMessage(mailboxId = text("mailboxId"))

        assertEquals(text("register"), message.toHex())
        assertEquals(190, message.size)
    }

    @Test
    fun `login message matches the golden vector`() {
        val message = SignedMessages.login(bytes("challenge"), text("mailboxId"))

        assertEquals(text("login"), message.toHex())
        assertEquals(114, message.size)
    }

    @Test
    fun `changing a signed field changes the message`() {
        val original = registrationMessage(mailboxId = text("mailboxId"))
        val altered = registrationMessage(mailboxId = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa")

        assertNotEquals(original.toHex(), altered.toHex())
    }

    private fun registrationMessage(mailboxId: String): ByteArray =
        SignedMessages.register(
            challenge = bytes("challenge"),
            mailboxId = mailboxId,
            identityKey = bytes("identityKey"),
            deliveryKeyHash = bytes("deliveryKeyHash"),
        )

    private fun text(name: String): String = vectors.getValue(name).jsonPrimitive.content

    private fun bytes(name: String): ByteArray = Base64Url.decode(text(name))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
