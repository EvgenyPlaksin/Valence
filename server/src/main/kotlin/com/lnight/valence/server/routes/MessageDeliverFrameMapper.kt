package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.frame.MessageDeliverFrame
import com.lnight.valence.server.domain.QueuedMessage

class MessageDeliverFrameMapper {
    fun toFrame(message: QueuedMessage): MessageDeliverFrame =
        MessageDeliverFrame(
            serverSeq = message.serverSeq,
            serverTs = message.serverTsMillis,
            envelope = Base64Url.encode(message.envelope),
        )
}
