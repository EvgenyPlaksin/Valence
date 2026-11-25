package com.lnight.valence.server.routes

import io.ktor.util.AttributeKey
import java.util.concurrent.atomic.AtomicBoolean

internal val SERVER_DRAINING = AttributeKey<AtomicBoolean>("valence-server-draining")
