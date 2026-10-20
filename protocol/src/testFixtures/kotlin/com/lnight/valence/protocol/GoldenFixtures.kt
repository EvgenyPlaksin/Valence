package com.lnight.valence.protocol

object GoldenFixtures {
    fun read(name: String): String {
        val stream =
            requireNotNull(GoldenFixtures::class.java.getResourceAsStream("/fixtures/$name.json")) {
                "Missing fixture $name"
            }
        return stream.bufferedReader().use { it.readText() }
    }
}