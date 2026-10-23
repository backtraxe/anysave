package io.github.backtraxe.anysave.log

import org.junit.Assert.assertEquals
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun `masks signed query parameters`() {
        val input = "hit xhr https://www.xiaohongshu.com/x?a=1&xsec_token=ABCdef&sign=999&z=2"
        assertEquals(
            "hit xhr https://www.xiaohongshu.com/x?a=1&xsec_token=…&sign=…&z=2",
            LogSanitizer.sanitize(input),
        )
    }

    @Test
    fun `keeps normal text unchanged`() {
        val input = "parsed aweme=6ad98 author=背影 images=3"
        assertEquals(input, LogSanitizer.sanitize(input))
    }
}
