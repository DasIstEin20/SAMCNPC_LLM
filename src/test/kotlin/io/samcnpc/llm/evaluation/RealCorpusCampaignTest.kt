package io.samcnpc.llm.evaluation

import com.google.gson.*
import io.samcnpc.llm.config.ResponseFormat
import io.samcnpc.llm.provider.OpenAiCompatibleProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Explicit opt-in; excluded from normal verification. Language results are evidence, not assertions of model quality. */
@EnabledIfSystemProperty(named = "samcnpc.realCorpus", matches = "true")
@Timeout(value = 4, unit = TimeUnit.HOURS)
internal class RealCorpusCampaignTest {
    @Test fun executeFrozenPairedCampaign() {
        val input = Path.of(System.getProperty("samcnpc.realCorpusInput")).toAbsolutePath().normalize()
        val output = Path.of(System.getProperty("samcnpc.realCorpusOutput")).toAbsolutePath().normalize()
        check(!Files.exists(output)) { "Preserve all previous attempts; use a fresh output directory" }
        val archive = FrozenCorpusArchive(input)
        Files.createDirectories(output)
        write(output.resolve("capture-manifest.json"), archive.manifest)
        val settings = CorpusRequestArchive.formats.associateWith(CorpusRequestArchive::settings)
        val providers = settings.mapValues { OpenAiCompatibleProvider(it.value) }
        val started = Instant.now().toString()
        val rows = JsonArray()
        var calls = 0
        try {
            val warmup = archive.rows.first { it.getAsJsonObject("case")["family"]?.asString == "samcnpc:navigate" }
            for (format in CorpusRequestArchive.formats) {
                val attempt = RealCorpusAttempt.run(warmup, format, null, providers.getValue(format))
                ++calls
                write(output.resolve("warmup-${format.name}.json"), attempt.record)
            }
            for (repetition in 0..2) for ((index, row) in archive.rows.withIndex()) {
                val formats = if ((repetition + index) % 2 == 0) CorpusRequestArchive.formats else CorpusRequestArchive.formats.reversed()
                for (format in formats) {
                    check(!Files.exists(output.resolve("stop"))) { "Campaign stop marker received" }
                    val record = JsonObject()
                    record.addProperty("index", index); record.addProperty("repetition", repetition)
                    record.addProperty("protocol", format.name); record.add("case", row.getAsJsonObject("case").deepCopy())
                    record.addProperty("scope", "DETACHED_LANGUAGE_ONLY_NO_ADMISSION")
                    record.addProperty("snapshotSha256", row["snapshotSha256"].asString)
                    record.add("fixture", row["fixture"].deepCopy())
                    val attempts = JsonArray()
                    val first = RealCorpusAttempt.run(row, format, null, providers.getValue(format))
                    ++calls; attempts.add(first.record)
                    // Only decoder/policy failures authorize repair. The expected answer is never feedback.
                    if (first.repairCode != null) {
                        val second = RealCorpusAttempt.run(row, format, first.repairCode, providers.getValue(format))
                        ++calls; attempts.add(second.record)
                    }
                    record.add("attempts", attempts)
                    record.addProperty("repairCalls", attempts.size() - 1)
                    record.add("firstScore", first.record["score"].deepCopy())
                    record.add("finalScore", attempts[attempts.size() - 1].asJsonObject["score"].deepCopy())
                    write(output.resolve("r$repetition-%03d-${format.name}.json".format(index)), record)
                    rows.add(record)
                    Files.writeString(output.resolve("progress.txt"), "${Instant.now()} scored=${rows.size()}/552 calls=$calls latest=${row.getAsJsonObject("case")["id"].asString} protocol=${format.name}\n")
                }
            }
            check(rows.size() == 552 && calls in 554..1106)
            val result = JsonObject()
            result.addProperty("status", "COMPLETED_MEASUREMENT_NOT_PROMOTION")
            result.addProperty("startedUtc", started); result.addProperty("endedUtc", Instant.now().toString())
            result.addProperty("scored", rows.size()); result.addProperty("providerCalls", calls)
            result.addProperty("warmupsExcluded", 2); result.addProperty("seed", "UNSPECIFIED_BACKEND_DEFAULT_NO_SEED_CLAIM")
            result.addProperty("cache", "BACKEND_DEFAULT_SHARED_CACHE_NO_SELECTIVE_FLUSH")
            result.addProperty("freshnessOrWorldAdmission", "NOT_PERFORMED_EXPIRED_DETACHED_SNAPSHOTS")
            result.add("settings", Gson().toJsonTree(settings)); result.add("rows", rows)
            write(output.resolve("campaign.json"), result)
        } finally { providers.values.forEach { it.close() } }
    }
    private fun write(path: Path, value: JsonElement) {
        check(!Files.exists(path))
        Files.writeString(path, GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(value) + "\n")
    }
}
