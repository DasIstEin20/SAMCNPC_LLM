package io.samcnpc.llm.evaluation

import com.google.gson.*
import io.samcnpc.llm.provider.LlmJson
import java.security.MessageDigest

/** Frozen test inputs only. Oracle-informed baseline setup is recorded, never inserted into model STATE. */
internal object CorpusFixtures {
    const val BASELINE_HASH = "15d8c10786dbf59924a154b9e8e5f55641b5ab779b53476bc0891e70e36bc12a"
    const val EXPANSION_HASH = "04dc39485a19ba5b98b36eb6f51ee88f91cb42ec24e87ca2c5f62914065333b5"
    val rows: List<JsonObject> by lazy {
        fun load(version: String, hash: String): List<JsonObject> {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/evaluation/$version/corpus.json")).use { it.readBytes() }
            check(sha(bytes) == hash)
            return LlmJson.parse(bytes.toString(Charsets.UTF_8), 262144)["cases"].asJsonArray.map { it.asJsonObject.deepCopy() }
        }
        val result = load("v1", BASELINE_HASH) + load("v3", EXPANSION_HASH)
        check(result.size == 92 && result.map { it["id"].asString }.distinct().size == 92)
        result
    }
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun position(x: Double, y: Double, z: Double) = JsonObject().also {
        it.addProperty("x", x); it.addProperty("y", y); it.addProperty("z", z)
    }

    fun setup(row: JsonObject): JsonObject {
        val declared = row["snapshotFixture"]
        if (declared != null) return declared.asJsonObject.deepCopy().also {
            it.addProperty("setupSource", "FROZEN_V3_FIXTURE")
            val running = it.getAsJsonObject("runningOperation")
            if (running != null) {
                val destination = running.getAsJsonObject("parameters").getAsJsonObject("destination")
                if (!it.has("npcPosition")) it.add("npcPosition", position(destination["x"].asDouble + 2.5,
                    destination["y"].asDouble, destination["z"].asDouble + 0.5))
                val chests = it.getAsJsonArray("chests") ?: JsonArray().also { chests -> it.add("chests", chests) }
                chests.add(JsonObject().also { chest -> chest.add("position", destination.deepCopy()); chest.add("contents", JsonObject()) })
                it.addProperty("nativePreparation", "Create the running task's required destination and start near it through the public API")
            } else if (!it.has("npcPosition")) it.add("npcPosition", position(0.0, 64.0, 0.0))
        }
        val setup = JsonObject()
        setup.addProperty("setupSource", "BASELINE_ORACLE_ENDPOINTS_AND_EXPLICIT_DELIVER_CARGO")
        val document = row["expectedOperation"].takeUnless { it.isJsonNull }?.asJsonObject
        val p = document?.getAsJsonObject("parameters")
        setup.add("npcPosition", p?.get("anchor")?.deepCopy() ?: p?.get("standing")?.deepCopy() ?: position(0.0, 64.0, 0.0))
        val inventory = JsonObject()
        if (document?.get("type")?.asString == "samcnpc:deliver") {
            val delivery = checkNotNull(p)
            inventory.add(delivery["itemId"].asString, delivery["quantity"].deepCopy())
        }
        setup.add("inventory", inventory)
        val chests = linkedMapOf<String, JsonObject>()
        fun addChest(value: JsonElement) {
            if (!value.isJsonObject) return
            val point = value.asJsonObject
            if (point.keySet() != setOf("x", "y", "z")) return
            chests.putIfAbsent(point.toString(), JsonObject().also { it.add("position", point.deepCopy()); it.add("contents", JsonObject()) })
        }
        if (p != null) {
            p["destination"]?.let { if (document["type"].asString == "samcnpc:deliver" || document["type"].asString == "samcnpc:lumberjack") addChest(it) }
            for (name in listOf("sources", "destinations")) p.getAsJsonObject(name)?.getAsJsonArray("positions")?.forEach(::addChest)
            p.getAsJsonObject("work")?.let { work ->
                for (name in listOf("sources", "destinations")) work.getAsJsonObject(name)?.getAsJsonArray("positions")?.forEach(::addChest)
            }
            // The explicit single-target UUID is a real passive non-player entity in the fixture.
            p["targetUuid"]?.let { setup.add("targetUuid", it.deepCopy()) }
            p["subjectUuid"]?.let { setup.add("targetUuid", it.deepCopy()) }
        }
        setup.add("chests", JsonArray().also { values -> chests.values.forEach(values::add) })
        val notes = row.getAsJsonArray("worldText")
        if (notes != null && !notes.isEmpty) {
            setup.add("memoryNotes", notes.deepCopy())
            setup.addProperty("textSurfaceProjection", "V1_WORLD_TEXT_AS_UNTRUSTED_MEMORY_FIXTURE_NOT_WORLD_OBSERVATION")
        }
        return setup
    }
}
