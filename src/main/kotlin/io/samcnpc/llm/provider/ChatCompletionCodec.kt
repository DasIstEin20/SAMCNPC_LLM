package io.samcnpc.llm.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.config.ResponseFormat
import java.net.http.HttpResponse

internal object ChatCompletionCodec {
    const val MAX_DECISION_BYTES = 16_384

    internal class Encoding(val bytes: ByteArray, val stateBytes: Int, val systemPromptBytes: Int,
                            val contractBytes: Int, val responseSchemaBytes: Int)

    fun request(request: LlmRequest, settings: ProviderSettings): ByteArray = encode(request, settings).bytes

    fun encode(request: LlmRequest, settings: ProviderSettings): Encoding {
        // Independent hard preparation bound permits measuring a request above its configured HTTP cap.
        require(request.systemPrompt.length <= 65536)
        val context = LlmJson.parse(request.contextJson, 262144)
        val schema = LlmJson.parse(request.responseSchemaJson, 65536)
        val root = JsonObject()
        root.addProperty("model", settings.model)
        root.addProperty("stream", false)
        root.addProperty("n", 1)
        root.addProperty("temperature", settings.temperature)
        root.addProperty("max_tokens", settings.maxOutputTokens)
        val messages = JsonArray()
        // Constrained decoding may enforce a grammar without showing it to the model.
        // Every mode needs the operation parameters in the actual prompt.
        val contractText = when (settings.responseFormat) {
            ResponseFormat.JSON_SCHEMA -> SchemaPrompt.describe(schema)
            ResponseFormat.JSON_OBJECT -> "OUTPUT_CONTRACT_JSON_SCHEMA\n" + schema.toString()
            ResponseFormat.SAM_EXPRESSION_V1 -> io.samcnpc.llm.expression.SamExpressionContract.describe(schema)
        }
        val system = request.systemPrompt + "\n" + contractText
        messages.add(message("system", system))
        messages.add(message("user", context.toString()))
        root.add("messages", messages)
        val format = JsonObject()
        var responseSchemaBytes = 0
        if (settings.responseFormat == ResponseFormat.JSON_SCHEMA) {
            format.addProperty("type", "json_schema")
            val contract = JsonObject()
            contract.addProperty("name", "samcnpc_decision")
            contract.addProperty("strict", true)
            val backendSchema = validationSchema(schema)
            responseSchemaBytes = LlmJson.utf8(backendSchema.toString()).size
            contract.add("schema", backendSchema)
            format.add("json_schema", contract)
        } else if (settings.responseFormat == ResponseFormat.JSON_OBJECT) format.addProperty("type", "json_object")
        if (settings.responseFormat != ResponseFormat.SAM_EXPRESSION_V1) root.add("response_format", format)
        return Encoding(LlmJson.utf8(root.toString()), LlmJson.utf8(context.toString()).size,
            LlmJson.utf8(request.systemPrompt).size, LlmJson.utf8(contractText).size, responseSchemaBytes)
    }

    // Prompt annotations stay visible once. Internal definition names carry no validation meaning.
    private fun validationSchema(schema: JsonObject): JsonElement {
        val definitionsKey = "$" + "defs"
        val referenceKey = "$" + "ref"
        val names = schema.getAsJsonObject(definitionsKey)?.keySet()?.mapIndexed { index, name -> name to "d$index" }?.toMap()
            ?: emptyMap()
        val prefix = "#/$definitionsKey/"
        fun encode(value: JsonElement, root: Boolean = false): JsonElement = when {
            value.isJsonObject -> JsonObject().also { result ->
                for ((key, child) in value.asJsonObject.entrySet()) {
                    if (key == "default" || key == "uniqueItems" && child.isJsonPrimitive && !child.asBoolean) continue
                    if (root && key == definitionsKey) {
                        val definitions = JsonObject()
                        for ((name, definition) in child.asJsonObject.entrySet())
                            definitions.add(names.getValue(name), encode(definition))
                        result.add(key, definitions)
                    } else if (key == referenceKey && child.asString.startsWith(prefix)) {
                        val path = child.asString.removePrefix(prefix)
                        val name = path.substringBefore('/')
                        val replacement = names[name]
                        result.addProperty(key, if (replacement == null) child.asString else prefix + replacement + path.removePrefix(name))
                    } else result.add(key, encode(child))
                }
            }
            value.isJsonArray -> JsonArray().also { result -> value.asJsonArray.forEach { result.add(encode(it)) } }
            else -> value.deepCopy()
        }
        return SharedSchemaShapes.compact(encode(schema, true).asJsonObject)
    }

    fun response(request: LlmRequest, http: HttpResponse<ByteArray>, format: ResponseFormat = ResponseFormat.JSON_SCHEMA): LlmResponse {
        val status = http.statusCode()
        fun failure(code: LlmFailure): LlmResponse.Failed {
            val retry = if (status == 429) http.headers().firstValue("Retry-After").orElse("")
                .toIntOrNull()?.coerceIn(0, 300) else null
            return LlmResponse.Failed(request.requestId, code, status, retry)
        }
        if (status != 200) return failure(when (status) {
            401, 403 -> LlmFailure.UNAUTHORIZED
            408, 504 -> LlmFailure.TIMEOUT
            429 -> LlmFailure.RATE_LIMITED
            in 500..599 -> LlmFailure.UNAVAILABLE
            else -> LlmFailure.INCOMPATIBLE
        })
        val contentType = http.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        if (contentType != "application/json" && !contentType.endsWith("+json")) return failure(LlmFailure.INCOMPATIBLE)
        val encoding = http.headers().firstValue("Content-Encoding").orElse("identity")
        if (encoding != "identity") return failure(LlmFailure.INCOMPATIBLE)
        val root = LlmJson.parse(LlmJson.decode(http.body()), 262_144)
        val choices = root.get("choices")
        if (choices == null || !choices.isJsonArray || choices.asJsonArray.size() != 1) return failure(LlmFailure.INVALID_OUTPUT)
        val choice = choices.asJsonArray[0]
        if (!choice.isJsonObject) return failure(LlmFailure.INVALID_OUTPUT)
        val item = choice.asJsonObject
        if (item.has("index") && integer(item.get("index")) != 0) return failure(LlmFailure.INVALID_OUTPUT)
        val message = item.get("message")
        if (message == null || !message.isJsonObject) return failure(LlmFailure.INVALID_OUTPUT)
        val body = message.asJsonObject
        if (text(body.get("role")) != "assistant") return failure(LlmFailure.INVALID_OUTPUT)
        if (body.has("refusal") && !body.get("refusal").isJsonNull) return failure(LlmFailure.REFUSED)
        if (body.has("function_call") && !body.get("function_call").isJsonNull) return failure(LlmFailure.INCOMPATIBLE)
        val calls = body.get("tool_calls")
        if (calls != null && !calls.isJsonNull && (!calls.isJsonArray || calls.asJsonArray.size() != 0)) return failure(LlmFailure.INCOMPATIBLE)
        when (text(item.get("finish_reason"))) {
            "stop" -> Unit
            "length" -> return failure(LlmFailure.INCOMPLETE)
            "content_filter" -> return failure(LlmFailure.REFUSED)
            "tool_calls", "function_call" -> return failure(LlmFailure.INCOMPATIBLE)
            else -> return failure(LlmFailure.INVALID_OUTPUT)
        }
        val content = text(body.get("content")) ?: return failure(LlmFailure.INVALID_OUTPUT)
        if (content.length > MAX_DECISION_BYTES || LlmJson.utf8(content).size > MAX_DECISION_BYTES)
            return failure(LlmFailure.RESPONSE_TOO_LARGE)
        if (format != ResponseFormat.SAM_EXPRESSION_V1) LlmJson.parse(content, MAX_DECISION_BYTES)
        val usage = root.get("usage")
        val counts = if (usage == null || usage.isJsonNull) null else {
            require(usage.isJsonObject) { "Invalid usage" }
            LlmUsage(optionalCount(usage.asJsonObject, "prompt_tokens"), optionalCount(usage.asJsonObject, "completion_tokens"))
        }
        return LlmResponse.Candidate(request.requestId, content, counts)
    }

    private fun message(role: String, content: String): JsonObject {
        val value = JsonObject()
        value.addProperty("role", role)
        value.addProperty("content", content)
        return value
    }

    private fun text(value: JsonElement?): String? =
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null

    private fun integer(value: JsonElement): Int {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid integer" }
        return value.asBigDecimal.intValueExact()
    }

    private fun optionalCount(objectValue: JsonObject, name: String): Int? {
        val value = objectValue.get(name) ?: return null
        val count = integer(value)
        require(count >= 0) { "Negative token usage" }
        return count
    }
}
