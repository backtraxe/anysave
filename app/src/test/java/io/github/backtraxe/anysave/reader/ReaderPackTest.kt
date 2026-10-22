package io.github.backtraxe.anysave.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPackTest {

    @Test
    fun `parses a valid pack with script and patterns`() {
        val script = "(function(){ return '{}'; })()"
        val pack = ReaderPackParser.parse(packJson(
            JSONObject()
                .put("id", "xiaohongshu")
                .put("embeddedPayloadScript", script)
                .put("sha256", ReaderPackParser.sha256(script))
                .put("responsePatterns", JSONArray().put("/api/sns/web/v1/feed")),
        ))

        assertEquals(1, pack.version)
        assertEquals(1, pack.readers.size)
        val override = pack.readers.single()
        assertEquals("xiaohongshu", override.readerId)
        assertEquals(script, override.embeddedPayloadScript)
        assertEquals(listOf("/api/sns/web/v1/feed"), override.responsePatterns)
    }

    @Test
    fun `accepts a patterns-only override`() {
        val pack = ReaderPackParser.parse(packJson(
            JSONObject()
                .put("id", "douyin")
                .put("responsePatterns", JSONArray().put("/aweme/v1/web/aweme/detail/")),
        ))
        assertEquals(1, pack.readers.size)
        assertEquals(null, pack.readers.single().embeddedPayloadScript)
    }

    @Test
    fun `rejects a mismatched sha256`() {
        val json = packJson(
            JSONObject()
                .put("id", "xiaohongshu")
                .put("embeddedPayloadScript", "tampered")
                .put("sha256", ReaderPackParser.sha256("original")),
        )
        val failure = runCatching { ReaderPackParser.parse(json) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("sha256"))
    }

    @Test
    fun `rejects unknown readers missing updates and bad versions`() {
        val unknown = packJson(JSONObject().put("id", "weibo").put("responsePatterns", JSONArray().put("/x")))
        assertTrue(runCatching { ReaderPackParser.parse(unknown) }.isFailure)

        val noUpdate = packJson(JSONObject().put("id", "douyin"))
        assertTrue(runCatching { ReaderPackParser.parse(noUpdate) }.isFailure)

        val badVersion = JSONObject()
            .put("version", 2)
            .put("readers", JSONArray().put(
                JSONObject().put("id", "douyin").put("responsePatterns", JSONArray().put("/x")),
            ))
        assertTrue(runCatching { ReaderPackParser.parse(badVersion.toString()) }.isFailure)

        assertTrue(runCatching { ReaderPackParser.parse("not json") }.isFailure)
    }

    @Test
    fun `sha256 is a stable hex digest`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ReaderPackParser.sha256("hello"),
        )
    }

    private fun packJson(vararg readers: JSONObject): String {
        val array = JSONArray()
        readers.forEach { array.put(it) }
        return JSONObject().put("version", 1).put("readers", array).toString()
    }
}
