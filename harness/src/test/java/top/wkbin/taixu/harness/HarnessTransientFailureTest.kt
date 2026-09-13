package top.wkbin.taixu.harness

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 瞬态故障识别与重试预算：
 * 上游 5xx（TransientHttpException）必须与断线 / 读超时一样，不被大上下文降级压到 1 次。
 */
class HarnessTransientFailureTest {

    private fun transient(code: Int) = TransientHttpException("HTTP $code", code, null as Long?)

    @Test
    fun `upstream 5xx counts as transient`() {
        assertTrue(HarnessProviderRunner.isTransientFailure(transient(524)))
        assertTrue(HarnessProviderRunner.isTransientFailure(transient(503)))
    }

    @Test
    fun `connection failures count as transient`() {
        assertTrue(HarnessProviderRunner.isTransientFailure(SocketException("Connection abort")))
        assertTrue(HarnessProviderRunner.isTransientFailure(SocketTimeoutException("timeout")))
        assertTrue(HarnessProviderRunner.isTransientFailure(SSLException("TLS broken")))
        assertTrue(HarnessProviderRunner.isTransientFailure(EOFException("unexpected end of stream")))
    }

    @Test
    fun `wrapped cause is inspected`() {
        val wrapped = IOException("stream failed", SocketTimeoutException("read timeout"))
        assertTrue(HarnessProviderRunner.isTransientFailure(wrapped))
    }

    @Test
    fun `ordinary errors are not transient`() {
        assertFalse(HarnessProviderRunner.isTransientFailure(IllegalStateException("bad request")))
        assertFalse(HarnessProviderRunner.isTransientFailure(IOException("disk full")))
    }

    @Test
    fun `self referencing cause terminates`() {
        val looping = IOException("loop").also { it.initCause(it) }
        assertFalse(HarnessProviderRunner.isTransientFailure(looping))
    }

    @Test
    fun `large context keeps transient retry budget`() {
        // 大上下文把预算降到 1；5xx 属瞬态，必须拉回 3
        val largeContextRetries = HarnessProviderRunner.maxNetworkRetriesFor(100_000, 3)
        assertEquals(1, largeContextRetries)
        assertEquals(3, HarnessProviderRunner.effectiveRetryBudget(largeContextRetries, transient(503)))
        assertEquals(3, HarnessProviderRunner.effectiveRetryBudget(largeContextRetries, SocketTimeoutException("t")))
    }

    @Test
    fun `large context does not raise non transient budget`() {
        val largeContextRetries = HarnessProviderRunner.maxNetworkRetriesFor(100_000, 3)
        assertEquals(1, HarnessProviderRunner.effectiveRetryBudget(largeContextRetries, IllegalStateException("x")))
    }

    @Test
    fun `transient never shrinks a richer configured budget`() {
        assertEquals(5, HarnessProviderRunner.effectiveRetryBudget(5, transient(500)))
        assertEquals(0, HarnessProviderRunner.effectiveRetryBudget(0, IllegalStateException("x")))
    }
}
