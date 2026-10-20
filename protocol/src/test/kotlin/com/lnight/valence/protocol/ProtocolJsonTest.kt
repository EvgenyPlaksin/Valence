package com.lnight.valence.protocol

import com.lnight.valence.protocol.frame.ErrorCode
import com.lnight.valence.protocol.frame.ErrorFrame
import com.lnight.valence.protocol.frame.MessageDeliverFrame
import com.lnight.valence.protocol.frame.SyncResumeFrame
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolJsonTest {
    @Test
    fun `encoded frames carry the type discriminator and the version`() {
        val text = ProtocolJson.encodeServerFrame(MessageDeliverFrame(serverSeq = 1, serverTs = 2, envelope = "AA"))
        val json = ProtocolJson.json.parseToJsonElement(text).jsonObject

        assertEquals("message.deliver", json.getValue("type").jsonPrimitive.content)
        assertEquals("1", json.getValue("v").jsonPrimitive.content)
    }

    @Test
    fun `unknown fields are ignored`() {
        val frame =
            ProtocolJson.decodeClientFrame("""{"type":"sync.resume","v":1,"afterServerSeq":5,"extra":true}""")

        assertEquals(SyncResumeFrame(afterServerSeq = 5), frame)
    }

    @Test
    fun `unknown error codes decode to UNKNOWN`() {
        val frame = ProtocolJson.decodeServerFrame("""{"type":"error","v":1,"code":"SOMETHING_NEW"}""")

        assertEquals(ErrorCode.UNKNOWN, assertIs<ErrorFrame>(frame).code)
    }
}