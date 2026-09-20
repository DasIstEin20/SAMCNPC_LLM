package io.samcnpc.llm

internal interface GoalRuntimeProbe : AutoCloseable {
    fun poll(): String?
    fun renderView(): Pair<java.util.UUID, String>?
}
