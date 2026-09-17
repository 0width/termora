package app.termora.plugins.mcp.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 消息构造与解析
 */
internal object JsonRpc {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    fun parse(text: String): JsonElement? {
        return runCatching { Json.parseToJsonElement(text) }.getOrNull()
    }

    /**
     * 提取方法名，没有 method 字段返回 null
     */
    fun method(message: JsonObject): String? {
        val primitive = message["method"] as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }

    /**
     * 提取请求 id，通知类消息（无 id 或 id 为 null）返回 null
     */
    fun id(message: JsonObject): JsonElement? {
        val id = message["id"] ?: return null
        return if (id is JsonNull) null else id
    }

    fun params(message: JsonObject): JsonObject {
        return message["params"] as? JsonObject ?: JsonObject(emptyMap())
    }

    fun result(id: JsonElement?, result: JsonObject): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", result)
        }
    }

    fun error(id: JsonElement?, code: Int, message: String): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }
    }

}
