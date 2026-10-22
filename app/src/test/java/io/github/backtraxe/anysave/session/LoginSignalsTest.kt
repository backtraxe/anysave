package io.github.backtraxe.anysave.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginSignalsTest {

    @Test
    fun `douyin nickname title means logged in`() {
        val result = LoginSignals.douyin("背影的抖音 - 抖音")
        assertEquals(true, result.loggedIn)
        assertEquals("背影", result.account)

        val loggedOut = LoginSignals.douyin("抖音-记录美好生活")
        assertEquals(false, loggedOut.loggedIn)
        assertEquals("", loggedOut.account)

        assertNull(LoginSignals.douyin("").loggedIn)
    }

    @Test
    fun `bilibili nav json determines login and account`() {
        val loggedIn = LoginSignals.bilibili(
            """{"code":0,"message":"0","data":{"isLogin":true,"uname":"someone"}}""",
        )
        assertEquals(true, loggedIn.loggedIn)
        assertEquals("someone", loggedIn.account)

        val loggedOut = LoginSignals.bilibili(
            """{"code":-101,"message":"账号未登录","data":{"isLogin":false}}""",
        )
        assertEquals(false, loggedOut.loggedIn)

        assertNull(LoginSignals.bilibili("<!DOCTYPE html>").loggedIn)
        assertNull(LoginSignals.bilibili("").loggedIn)
    }

    @Test
    fun `xiaohongshu state determines login and nickname`() {
        val loggedIn = LoginSignals.xiaohongshu("""{"logged":true,"nickname":"小书"}""")
        assertEquals(true, loggedIn.loggedIn)
        assertEquals("小书", loggedIn.account)

        val loggedOut = LoginSignals.xiaohongshu("""{"logged":false,"nickname":""}""")
        assertEquals(false, loggedOut.loggedIn)

        assertNull(LoginSignals.xiaohongshu("""{"nickname":"x"}""").loggedIn)
        assertNull(LoginSignals.xiaohongshu("not json").loggedIn)
    }
}
