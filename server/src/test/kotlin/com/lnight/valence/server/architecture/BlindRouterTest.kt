package com.lnight.valence.server.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

class BlindRouterTest {
    private val loggingCall = Regex("""\.(trace|debug|info|warn|error)\(|println\(""")
    private val sensitiveName =
        Regex("""(?i)envelope|mailbox|deliverykey|delivery-key|identitykey|accesstoken|refreshtoken|proof""")

    @Test
    fun `log statements never mention identifiers or payload fields`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                file.text.lines().none { line ->
                    loggingCall.containsMatchIn(line) &&
                        sensitiveName.containsMatchIn(line)
                }
            }
    }
}
