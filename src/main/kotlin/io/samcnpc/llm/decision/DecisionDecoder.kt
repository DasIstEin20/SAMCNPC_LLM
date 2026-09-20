package io.samcnpc.llm.decision

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.llm.provider.LlmJson
import java.io.IOException
import java.util.UUID

/** Strict small envelope; typed operation/change semantics stay in the published Behavior decoder. */
internal object DecisionDecoder {
    const val VERSION = 1
    const val MAX_BYTES = 16 * 1024
    private val fields = setOf("schemaVersion", "contextId", "decision", "summary", "operation", "change", "question", "wait")

    fun decode(json: String): DecisionDecodeResult {
        if (json.length > MAX_BYTES) return DecisionDecodeResult.Rejected("DECISION_TOO_LARGE")
        return try {
            if (LlmJson.utf8(json).size > MAX_BYTES) return DecisionDecodeResult.Rejected("DECISION_TOO_LARGE")
            val root = LlmJson.parse(json, MAX_BYTES)
            require(root.keySet() == fields)
            require(integer(root["schemaVersion"]) == VERSION)
            val contextText = text(root["contextId"], 36)
            val contextId = UUID.fromString(contextText)
            require(contextText.length == 36 && contextId.toString() == contextText)
            val kind = DecisionKind.valueOf(text(root["decision"], 16))
            val summary = text(root["summary"], 256)
            val operation = root["operation"]
            val change = root["change"]
            val question = root["question"]
            val wait = root["wait"]
            val action: DecisionAction = when (kind) {
                DecisionKind.CONTINUE -> {
                    require(operation.isJsonNull && change.isJsonNull && question.isJsonNull && wait.isJsonNull)
                    DecisionAction.Continue
                }
                DecisionKind.ASSIGN -> {
                    require(operation.isJsonObject && change.isJsonNull && question.isJsonNull && wait.isJsonNull)
                    when (val decoded = OperationDocumentApi.decodeOrder(operation.toString())) {
                        is OperationDocumentResult.Accepted -> DecisionAction.Assign(decoded.value)
                        is OperationDocumentResult.Rejected -> return DecisionDecodeResult.Rejected("OPERATION_" + decoded.code)
                    }
                }
                DecisionKind.AMEND -> {
                    require(operation.isJsonNull && change.isJsonObject && question.isJsonNull && wait.isJsonNull)
                    when (val decoded = OperationDocumentApi.decodeChange(change.toString())) {
                        is OperationDocumentResult.Accepted -> DecisionAction.Amend(decoded.value)
                        is OperationDocumentResult.Rejected -> return DecisionDecodeResult.Rejected("CHANGE_" + decoded.code)
                    }
                }
                DecisionKind.PAUSE, DecisionKind.RESUME, DecisionKind.CANCEL -> {
                    require(operation.isJsonNull && change.isJsonNull && question.isJsonNull && wait.isJsonNull)
                    DecisionAction.Control(OperationControl.valueOf(kind.name))
                }
                DecisionKind.WAIT -> {
                    require(operation.isJsonNull && change.isJsonNull && question.isJsonNull && wait.isJsonObject)
                    decodeWait(wait.asJsonObject)
                }
                DecisionKind.ASK_USER -> {
                    require(operation.isJsonNull && change.isJsonNull && wait.isJsonNull)
                    val value = text(question, 256)
                    require(value.isNotBlank())
                    DecisionAction.AskUser(value)
                }
            }
            DecisionDecodeResult.Accepted(LlmDecision(contextId, kind, action, summary))
        } catch (_: IllegalArgumentException) {
            DecisionDecodeResult.Rejected("INVALID_DECISION")
        } catch (_: IOException) {
            DecisionDecodeResult.Rejected("INVALID_DECISION")
        } catch (_: com.google.gson.JsonParseException) {
            DecisionDecodeResult.Rejected("INVALID_DECISION")
        } catch (_: ArithmeticException) {
            DecisionDecodeResult.Rejected("INVALID_DECISION")
        }
    }

    private fun decodeWait(value: JsonObject): DecisionAction.Wait {
        require(value.keySet() == setOf("trigger", "ticks"))
        val trigger = WaitTrigger.valueOf(text(value["trigger"], 32))
        val ticks = if (value["ticks"].isJsonNull) null else integer(value["ticks"])
        if (trigger == WaitTrigger.DEADLINE) require(ticks != null && ticks in 20..1200) else require(ticks == null)
        return DecisionAction.Wait(trigger, ticks)
    }

    private fun text(value: JsonElement, limit: Int): String {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        val text = value.asString
        require(text.length <= limit)
        return text
    }

    private fun integer(value: JsonElement): Int {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.intValueExact()
    }
}
