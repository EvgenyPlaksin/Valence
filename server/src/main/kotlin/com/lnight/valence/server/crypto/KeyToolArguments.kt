package com.lnight.valence.server.crypto

import java.nio.file.Path

internal fun keyOutputDirectory(args: Array<String>): Path {
    require(args.size == 1) { "Pass one absolute path to an existing private key output directory" }
    val path = Path.of(args[0])
    require(path.isAbsolute) { "The private key output directory must be an absolute path" }
    return path
}
