package com.lnight.valence.core.database

import com.lnight.valence.core.model.Outbox

data class StoredOutboxItem(
    val outbox: Outbox,
    val envelope: ImmutableBytes,
)
