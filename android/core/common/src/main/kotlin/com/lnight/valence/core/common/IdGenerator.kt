package com.lnight.valence.core.common

import com.lnight.valence.core.model.MessageId

interface IdGenerator {
    fun newMessageId(): MessageId
}
