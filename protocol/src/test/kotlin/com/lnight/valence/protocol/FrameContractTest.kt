package com.lnight.valence.protocol

import com.lnight.valence.protocol.frame.ClientFrame
import com.lnight.valence.protocol.frame.DeliveryConfirmFrame
import com.lnight.valence.protocol.frame.ErrorCode
import com.lnight.valence.protocol.frame.ErrorFrame
import com.lnight.valence.protocol.frame.MessageDeliverFrame
import com.lnight.valence.protocol.frame.ServerFrame
import com.lnight.valence.protocol.frame.SyncResumeFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FrameContractTest {
    @Test
    fun `message deliver frame matches its fixture`() {
        val frame = ContractAssertions.roundTrip("message-deliver", ServerFrame.serializer())

        assertEquals(884_213L, assertIs<MessageDeliverFrame>(frame).serverSeq)
    }

    @Test
    fun `error frame matches its fixture`() {
        val frame = assertIs<ErrorFrame>(ContractAssertions.roundTrip("error", ServerFrame.serializer()))

        assertEquals(ErrorCode.RATE_LIMITED, frame.code)
        assertEquals(2_000L, frame.retryAfterMs)
    }

    @Test
    fun `delivery confirm frame matches its fixture`() {
        val frame = ContractAssertions.roundTrip("delivery-confirm", ClientFrame.serializer())

        assertEquals(884_213L, assertIs<DeliveryConfirmFrame>(frame).upToServerSeq)
    }

    @Test
    fun `sync resume frame matches its fixture`() {
        val frame = ContractAssertions.roundTrip("sync-resume", ClientFrame.serializer())

        assertEquals(884_200L, assertIs<SyncResumeFrame>(frame).afterServerSeq)
    }
}