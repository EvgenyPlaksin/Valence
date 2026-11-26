package com.lnight.valence.core.database

import com.lnight.valence.core.model.Message

data class InboundMessageCommit(
    val message: Message,
    val session: SignalSessionStorage,
    val consumedOneTimePreKeyId: Int?,
    val consumedOneTimeKyberPreKeyId: Int?,
)

sealed interface InboundCommitResult {
    data object Stored : InboundCommitResult

    data object Duplicate : InboundCommitResult
}
