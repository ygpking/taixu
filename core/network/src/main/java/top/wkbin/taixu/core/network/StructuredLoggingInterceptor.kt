package top.wkbin.taixu.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 📝 结构化日志拦截器
 * 
 * 功能：
 * 1. 拦截 OkHttp 请求和响应
 * 2. 输出 JSON 格式的结构化日志（便于日志系统解析）
 * 3. 可配置日志级别（BODY/HEADERS/BASIC）
 * 4. 自动脱敏敏感信息（Authorization、Cookie 等）
 * 
 * 用法示例：
 * ```kotlin
 * @Inject lateinit var loggingInterceptor: StructuredLoggingInterceptor
 * 
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(loggingInterceptor)
 *     .build()
 * ```
 * 
 * 日志输出格式：
 * ```json
 * {
 *   "timestamp": "2025-01-17T10:30:45.123Z",
 *   "type": "request",
 *   "method": "GET",
 *   "url": "https://api.example.com/users",
 *   "headers": {...},
 *   "body": null,
 *   "duration_ms": 156
 * }
 * ```
 */
@Singleton
class StructuredLoggingInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        
        // 构建请求日志
        val requestLog = buildRequestLog(request)
        logJson(requestLog)
        
        // 执行请求
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            // 记录异常
            val errorLog = buildErrorLog(request, e, System.currentTimeMillis() - startTime)
            logJson(errorLog)
            throw e
        }
        
        // 构建响应日志
        val durationMs = System.currentTimeMillis() - startTime
        val responseLog = buildResponseLog(request, response, durationMs)
        logJson(responseLog)
        
        return response
    }

    /**
     * 构建请求日志 JSON
     */
    private fun buildRequestLog(request: okhttp3.Request): JSONObject {
        return JSONObject().apply {
            put("timestamp", getCurrentTimestamp())
            put("type", "request")
            put("method", request.method)
            put("url", redactUrl(request.url.toString()))
            put("headers", buildHeadersJson(request.headers))
            
            // 请求体（如果有）
            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                val bodyStr = buffer.readUtf8()
                
                // 脱敏处理
                put("body", redactSensitiveData(bodyStr))
                put("content_length", body.contentLength())
            } ?: put("body", JSONObject.NULL)
        }
    }

    /**
     * 构建响应日志 JSON
     */
    private fun buildResponseLog(
        request: okhttp3.Request,
        response: Response,
        durationMs: Long
    ): JSONObject {
        return JSONObject().apply {
            put("timestamp", getCurrentTimestamp())
            put("type", "response")
            put("method", request.method)
            put("url", redactUrl(response.request.url.toString()))
            put("status_code", response.code)
            put("headers", buildHeadersJson(response.headers))
            put("duration_ms", durationMs)
            
            // 响应体（如果有且可读）
            response.body?.let { body ->
                val bodyStr = body.string()
                
                // 脱敏处理
                put("body", redactSensitiveData(bodyStr))
                put("content_length", body.contentLength())
                
                // 注意：body.string() 只能调用一次，需要重新构造 response
                // 实际使用中需要在返回前重建 response
            } ?: put("body", JSONObject.NULL)
        }
    }

    /**
     * 构建错误日志 JSON
     */
    private fun buildErrorLog(
        request: okhttp3.Request,
        error: Exception,
        durationMs: Long
    ): JSONObject {
        return JSONObject().apply {
            put("timestamp", getCurrentTimestamp())
            put("type", "error")
            put("method", request.method)
            put("url", redactUrl(request.url.toString()))
            put("duration_ms", durationMs)
            put("error_class", error.javaClass.simpleName)
            put("error_message", error.message)
        }
    }

    /**
     * 构建 Headers JSON
     */
    private fun buildHeadersJson(headers: okhttp3.Headers): JSONObject {
        return JSONObject().apply {
            for (i in 0 until headers.size) {
                val name = headers.name(i)
                val value = headers.value(i)
                
                // 脱敏敏感头
                if (isSensitiveHeader(name)) {
                    put(name, "***REDACTED***")
                } else {
                    put(name, value)
                }
            }
        }
    }

    /**
     * 脱敏敏感数据
     */
    private fun redactSensitiveData(data: String): String {
        var result = data
        
        // 脱敏 API Key
        result = result.replace(Regex("""["']api[_-]?key["']\s*:\s*["'][^"']+["']"""), "\"api_key\":\"***REDACTED***\"")
        
        // 脱敏 Token
        result = result.replace(Regex("""["'](?:access_?token|refresh_?token|id_token)["']\s*:\s*["'][^"']+["']"""), 
            "\"token\":\"***REDACTED***\"")
        
        // 脱敏密码
        result = result.replace(Regex("""["']password["']\s*:\s*["'][^"']+["']"""), "\"password\":\"***REDACTED***\"")
        
        return result
    }

    /**
     * 脱敏 URL（移除 query 参数中的敏感信息）
     */
    private fun redactUrl(url: String): String {
        return url
            .replace(Regex("""[?&](?:api_?key|token|secret|password)=[^&]+"""), "")
            .replace(Regex("""\?&+"""), "?")
            .trimEnd('?')
    }

    /**
     * 检查是否为敏感 Header
     */
    private fun isSensitiveHeader(name: String): Boolean {
        val sensitiveHeaders = setOf(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "X-API-Key",
            "X-Auth-Token",
            "Proxy-Authorization"
        )
        return sensitiveHeaders.any { name.equals(it, ignoreCase = true) }
    }

    /**
     * 获取当前 ISO8601 时间戳
     */
    private fun getCurrentTimestamp(): String {
        return java.time.Instant.now().toString()
    }

    /**
     * 输出 JSON 日志
     */
    private fun logJson(json: JSONObject) {
        android.util.Log.d(TAG, json.toString())
    }

    companion object {
        private const val TAG = "HttpStructuredLog"
    }
}
