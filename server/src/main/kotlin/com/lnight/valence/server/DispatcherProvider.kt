package com.lnight.valence.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

interface DispatcherProvider {
    fun database(parallelism: Int): CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun database(parallelism: Int): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(parallelism)
}
