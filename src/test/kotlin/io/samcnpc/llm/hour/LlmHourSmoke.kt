package io.samcnpc.llm.hour

import com.google.gson.GsonBuilder
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.llm.SamcnpcLlm
import net.minecraft.core.BlockPos
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Mod.EventBusSubscriber(modid=SamcnpcLlm.MOD_ID)
internal object LlmHourSmoke {
    private val mode = System.getProperty("samcnpc.llmHour")
    private val baseline get() = mode == "BASELINE"
    private val probe get() = mode != "FULL"
    private val clock = HourClock()
    private val metrics = HourMeasurements()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val forced = mutableSetOf<Pair<Int,Int>>()
    private var player: ServerPlayer? = null
    private var channel: EmbeddedChannel? = null
    private var scenes = emptyList<HourArena>()
    private var inference: HourInference? = null
    private var start = 0L
    private var tickStart = 0L
    private var llmNanos = 0L
    private var ticks = 0
    private var setup = false
    private var ready = false
    private var mutated = false
    private var done = false
    private var failed = false
    private var closed = false
    private var offlineStart = 0L
    private var offlineEnd = 0L
    private var offlineCalls = 0
    private var sourceHash = ""
    private var finalInference: Map<String,Any>? = null

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    fun begin(event: TickEvent.ServerTickEvent) {
        if (mode == null || done || failed || event.phase != TickEvent.Phase.START) return
        tickStart = System.nanoTime(); mutated = false; llmNanos = 0
    }
    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (mode == null || done || failed || event.phase != TickEvent.Phase.END) return
        val server = event.server
        try {
            check(++ticks <= if (probe) 12000 else 108000)
            if (ticks < 20) return
            if (!setup) { initialize(server); mutated = true; return }
            check((System.nanoTime()-start)/1_000_000_000.0 < if (probe) 600 else 5400)
            if (!ready) {
                if (!scenes.all { it.body.snapshot().onGround }) return
                ready = true
            }
            val a = checkNotNull(player)
            val workStart = System.nanoTime()
            var active = true
            for (scene in scenes) {
                val task = scene.observe(ticks)
                if (task?.state == OperationTaskState.COMPLETED && scene.task != null) scene.terminal(ticks,task)
                active = active && scene.task != null && task?.state in setOf(OperationTaskState.RUNNING,OperationTaskState.WAITING) &&
                    ticks-scene.lastMovedTick <= 20
            }
            clock.observe(System.nanoTime(),active)
            val engine = inference
            if (baseline) {
                for (scene in scenes) if (scene.task == null) {
                    val view = checkNotNull(OperationSupervisionApi.observe(server,a,scene.handle.npcUuid).observation)
                    val reply = OperationSupervisionApi.assign(server,a,scene.handle.npcUuid,
                        OperationAssignmentRequest(view.task?.taskId,view.observedTick,view.observedTick+100,scene.order))
                    check(reply.result.status == NpcActionStatus.SUCCEEDED) { reply.toString() }
                    scene.task = checkNotNull(reply.observation?.task?.taskId); scene.taskStartedTick = ticks
                }
            } else {
                checkNotNull(engine)
                engine.poll((System.nanoTime()-start)/1_000_000L,ticks)
            }
            llmNanos = if (baseline) 0 else System.nanoTime()-workStart
            if (engine != null) {
                if (offlineStart == 0L && active && clock.seconds >= if (probe) 10 else 60) {
                    engine.setOffline(true); offlineStart = System.nanoTime(); offlineCalls = engine.calls; mutated = true
                }
                if (offlineStart != 0L && offlineEnd == 0L) {
                    check(engine.calls == offlineCalls && scenes.all { it.task != null })
                    if ((System.nanoTime()-offlineStart)/1_000_000_000.0 >= if (probe) 20 else 1200) {
                        engine.setOffline(false); offlineEnd = System.nanoTime(); mutated = true
                    }
                }
            }
            val combatInterval = if (probe) 200 else 2400
            for (scene in scenes) scene.combatFixture(ticks,combatInterval,scene.task != null)
            if (ticks % 600 == 0) { metrics.heap(); report("RUNNING"); mutated = true }
            if (clock.reached(probe) && scenes.all { it.completed > 0 } &&
                scenes.filter { it.combat }.all { it.kills >= if (probe) 1 else 10 } &&
                (baseline || offlineEnd != 0L && engine?.settled() == true)) {
                metrics.verify()
                if (!baseline) check(checkNotNull(engine).slowTicks >= 20)
                check(scenes.all { it.distance >= if (probe) 100 else 3000 })
                done = true
                finalInference = inference?.evidence()
                report("AWAITING_NATIVE_STOP")
                Files.writeString(Path.of("llm-hour-samples.json"),gson.toJson(metrics.raw())+"\n")
                server.saveEverything(false,true,true); server.halt(false)
            }
        } catch (error: Exception) {
            failed = true
            report("FAIL",error.stackTraceToString())
            Files.writeString(Path.of("llm-hour-result.txt"),"FAIL\n"+error.stackTraceToString())
            server.halt(false)
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    fun end(event: TickEvent.ServerTickEvent) {
        if (mode == null || !ready || done || failed || mutated || ticks < 200 || event.phase != TickEvent.Phase.END) return
        metrics.sample(llmNanos,System.nanoTime()-tickStart)
    }
    private fun initialize(server: MinecraftServer) {
        check(server.isDedicatedServer && mode in setOf("PROBE","FULL","BASELINE"))
        sourceHash = System.getProperty("samcnpc.llmHourSourceHash","")
        check(sourceHash.matches(Regex("[0-9a-f]{64}")))
        val level = server.overworld()
        server.setDifficulty(Difficulty.NORMAL,true)
        level.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server)
        level.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false,server)
        level.gameRules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server)
        level.dayTime = 18000L; level.setWeatherParameters(100000,0,false,false)
        val connection = Connection(PacketFlow.SERVERBOUND)
        channel = EmbeddedChannel(connection); connection.setProtocol(ConnectionProtocol.PLAY)
        val actor = ServerPlayer(server,level,GameProfile(UUID.randomUUID(),"LlmHour"))
        server.playerList.placeNewPlayer(connection,actor); actor.setGameMode(GameType.SPECTATOR)
        player = actor
        val center = BlockPos(32,-59,32)
        actor.teleportTo(level,center.x+48.5,center.y+7.0,center.z+24.5,0F,0F)
        for (cx in ((center.x-20) shr 4)..((center.x+116) shr 4))
            for (cz in ((center.z-20) shr 4)..((center.z+68) shr 4)) {
                check(level.setChunkForced(cx,cz,true)); forced.add(cx to cz)
            }
        val rounds = if (probe) 1 else System.getProperty("samcnpc.llmHourRounds","24").toInt()
        val copies = if (probe) 2 else 4
        check(rounds in 1..64)
        // One isolated fixed-seed world; fixture setup is excluded from active/performance time.
        scenes = (0..5).map { HourArena(server,actor,it,center.offset((it%3)*48,0,(it/3)*48),rounds,copies) }
        if (!baseline) inference = HourInference(server,actor,scenes,metrics)
        start = System.nanoTime(); setup = true
        report("RUNNING")
    }
    private fun report(status: String, failure: String? = null) {
        val report = linkedMapOf("status" to status,"mode" to mode,"sourceHash" to sourceHash,
            "activeSeconds" to clock.seconds,"activeTicks" to clock.activeTicks,"excludedClockGaps" to clock.excludedGaps,
            "elapsedSeconds" to if (start == 0L) 0.0 else (System.nanoTime()-start)/1_000_000_000.0,
            "ticks" to ticks,"npcCount" to scenes.size,"scenes" to scenes.map { it.evidence() },
            "providerDisconnectedSeconds" to if (offlineStart == 0L) 0.0 else
                ((if (offlineEnd == 0L) System.nanoTime() else offlineEnd)-offlineStart)/1_000_000_000.0,
            "inference" to (finalInference ?: inference?.evidence()),"performance" to metrics.summary(),
            "scope" to "Restricted PATROL policy; production scheduler/context/admission plus native movement and visible melee. Goal-controller modes have separate acceptance.",
            "failure" to failure)
        Files.writeString(Path.of("llm-hour-progress.json"),gson.toJson(report)+"\n")
    }
    @SubscribeEvent
    fun stopping(event: ServerStoppingEvent) {
        if (mode == null || closed) return
        closed = true
        try {
            inference?.close()
            for (scene in scenes) scene.cancel()
            scenes.forEach { it.close() }
            player?.let { event.server.playerList.remove(it) }; player = null
            channel?.finishAndReleaseAll(); channel = null
            for ((x,z) in forced) event.server.overworld().setChunkForced(x,z,false)
        } catch (error: Exception) {
            failed = true; report("FAIL",error.stackTraceToString())
            Files.writeString(Path.of("llm-hour-result.txt"),"FAIL during cleanup\n"+error.stackTraceToString())
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    fun stopped(event: ServerStoppedEvent) {
        if (mode == null || !done || failed) return
        val workers = Thread.getAllStackTraces().keys.count { it.isAlive &&
            (it.name == "samcnpc-llm-inference" || it.name == "samcnpc-http-emulator") }
        if (workers != 0) {
            report("FAIL","Workers retained after native stop: "+workers)
            Files.writeString(Path.of("llm-hour-result.txt"),"FAIL workers retained "+workers)
            return
        }
        report("PASS")
        Files.writeString(Path.of("llm-hour-result.txt"),"PASS mode="+mode+" activeSeconds="+clock.seconds+
            " activeTicks="+clock.activeTicks+" completedTasks="+scenes.sumOf { it.completed }+" nativeStop=true retainedWorkers=0\n")
    }
}
