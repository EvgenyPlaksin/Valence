package com.lnight.valence.core.network.plaintext

import com.lnight.valence.core.model.PlaintextCodec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaintextCodecModule {
    @Binds
    abstract fun bindPlaintextCodec(implementation: PlaintextCodecImpl): PlaintextCodec
}
