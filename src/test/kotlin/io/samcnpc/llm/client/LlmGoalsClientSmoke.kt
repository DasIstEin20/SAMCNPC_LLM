package io.samcnpc.llm.client

import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.SamcnpcLlm
import io.samcnpc.llm.TranslatorRuntimeProbe
import io.samcnpc.llm.SupervisorRuntimeProbe
import io.samcnpc.llm.PlannerRuntimeProbe
import io.samcnpc.llm.GoalRuntimeProbe
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.registries.Registries
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.hypot

/** Actual integrated client/server gameplay; detached render counters are the only cross-thread test state. */
@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID, value = [Dist.CLIENT])
internal object LlmGoalsClientSmoke {
    private val planner = java.lang.Boolean.getBoolean("samcnpc.plannerClientSmoke")
    private val supervisor = java.lang.Boolean.getBoolean("samcnpc.supervisorClientSmoke")
    private val enabled = planner || supervisor || java.lang.Boolean.getBoolean("samcnpc.goalsClientSmoke")
    private val report = if (planner) "client-planner-result.txt" else if (supervisor) "client-supervisor-result.txt" else "client-goals-result.txt"
    private data class View(val npc: UUID, val name: String)
    @Volatile private var worldId: String? = null
    @Volatile private var view: View? = null
    @Volatile private var result: String? = null
    private val frames = ConcurrentHashMap<UUID, AtomicInteger>()
    private val walking = ConcurrentHashMap<UUID, AtomicInteger>()
    private val names = ConcurrentHashMap<UUID, String>()
    private var clientTicks = 0
    private var done = false
    // Server-thread only.
    private var probe: GoalRuntimeProbe? = null

    @SubscribeEvent
    fun client(event: TickEvent.ClientTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        val minecraft = Minecraft.getInstance()
        try {
            check(++clientTicks < 12000) { "LLM goal client smoke timed out" }
            if (minecraft.screen is AccessibilityOnboardingScreen && minecraft.overlay == null) minecraft.screen?.onClose()
            if (worldId == null && minecraft.screen is TitleScreen && minecraft.overlay == null) {
                val id = "llm-goals-" + System.currentTimeMillis(); worldId = id
                minecraft.options.pauseOnLostFocus = false
                minecraft.options.renderDistance().set(6); minecraft.options.simulationDistance().set(8)
                minecraft.options.framerateLimit().set(60)
                val settings = LevelSettings(id, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                    GameRules(), WorldDataConfiguration.DEFAULT)
                minecraft.createWorldOpenFlows().createFreshLevel(id, settings, WorldOptions(0L, false, false),
                    { registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() })
            }
            val current = view
            val player = minecraft.player
            val body = current?.let { snapshot -> minecraft.level?.entitiesForRendering()?.firstOrNull { it.uuid == snapshot.npc } }
            if (body != null && player != null) {
                val dx = body.x - player.x; val dz = body.z - player.z
                player.yRot = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
                player.xRot = (-Math.toDegrees(atan2(body.eyeY - player.eyeY, hypot(dx, dz)))).toFloat()
            }
            val outcome = result ?: return
            check(outcome.startsWith("PASS")) { outcome }
            check(names.size == (if (planner) PlannerRuntimeProbe.CASES.size else if (supervisor) SupervisorRuntimeProbe.CASES.size else 8) && frames.values.all { it.get() >= 2 })
            check(names.filterValues { it in movingCases }.keys.all { (walking[it]?.get() ?: 0) >= 2 })
            Files.writeString(Path.of(report), outcome + "\nrenderedCases=" + names.size +
                " frames=" + names.entries.associate { it.value to frames[it.key]?.get() } +
                " walking=" + names.entries.associate { it.value to walking[it.key]?.get() } + "\n")
            done = true; minecraft.stop()
        } catch (failure: Exception) {
            Files.writeString(Path.of(report), "FAIL " + failure.stackTraceToString())
            done = true; minecraft.stop()
        }
    }

    @SubscribeEvent
    fun server(event: TickEvent.ServerTickEvent) {
        if (!enabled || result != null || event.phase != TickEvent.Phase.END) return
        val id = worldId ?: return
        val server = event.server
        if (server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() != id) return
        val actor = server.playerList.players.firstOrNull() ?: return
        try {
            var current = probe
            if (current == null) {
                server.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server)
                server.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false, server)
                val ground = actor.serverLevel().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, actor.blockPosition())
                val origin = NpcPosition(ground.x + 0.5, ground.y + 1.0, ground.z + 0.5)
                actor.setGameMode(GameType.SPECTATOR)
                actor.teleportTo(actor.serverLevel(), origin.x + 20, origin.y + 7, origin.z + 14, 0F, 30F)
                val canFinish: (UUID) -> Boolean = { npc ->
                    val name = names[npc]
                    (frames[npc]?.get() ?: 0) >= 2 &&
                        (name !in movingCases || (walking[npc]?.get() ?: 0) >= 2)
                }
                current = if (planner) PlannerRuntimeProbe(server, actor, origin, canFinish)
                    else if (supervisor) SupervisorRuntimeProbe(server, actor, origin, canFinish)
                    else TranslatorRuntimeProbe(server, actor, origin, canFinish)
                probe = current
            }
            val outcome = current.poll()
            val snapshot = current.renderView()
            if (snapshot != null) {
                names[snapshot.first] = snapshot.second
                view = View(snapshot.first, snapshot.second)
            }
            if (outcome != null) {
                current.close()
                result = "PASS integratedServer=true actualCommands=true " + outcome
            }
        } catch (failure: Exception) {
            probe?.close()
            result = "FAIL " + failure.stackTraceToString()
        }
    }

    @SubscribeEvent
    fun rendered(event: RenderLivingEvent.Post<*, *>) {
        if (!enabled || done) return
        val current = view ?: return
        if (event.entity.uuid != current.npc) return
        val model = event.renderer.model as? PlayerModel<*> ?: error("NPC has no player renderer")
        frames.computeIfAbsent(current.npc) { AtomicInteger() }.incrementAndGet()
        if (kotlin.math.abs(model.rightLeg.xRot) > 0.1F)
            walking.computeIfAbsent(current.npc) { AtomicInteger() }.incrementAndGet()
    }

    private val movingCases = if (planner) PlannerRuntimeProbe.MOVING else if (supervisor) SupervisorRuntimeProbe.MOVING else setOf("clarify", "deliver", "lumberjack", "pause", "offline")
}
