package com.github.noamm9.vampire.features.impl.vampire

import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.EntityUnloadEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
import com.github.noamm9.vampire.utils.VampireBoss.findBossStand
import com.github.noamm9.vampire.utils.VampireBoss.standsAround
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3

/**
 * Toggling this feature turns the Vampire debug chat messages on or off; the other features read [enabled].
 *
 * It also records the Tier V Blood Ichor attack, to collect what a future Ichor feature can detect:
 * the "ICHOR x.xs" timer on the boss nametag, every entity that shows up near the boss while the timer runs
 * and shortly after, name changes of those entities, sounds, a particle summary, and chat/titles mentioning Ichor.
 */
object Debug: Feature(
    description = "Prints Debug from Auto Slayer, Blood Ichor and Tiny Dancer in chat.",
    name = "Debug",
    toggled = false
) {
    private val ichorRegex = Regex("ICHOR\\s+(\\d+(?:\\.\\d+)?)S")
    /** Ticks to keep recording after the ICHOR timer ends, to catch whatever the attack spawns when it goes off. */
    private const val AFTER_TICKS = 100
    /** Radius around the boss in which new entities are recorded. */
    private const val SCAN_RADIUS = 20.0

    private var ticks = 0L
    /** Tick the current Ichor timer was first seen, or null outside a recording. */
    private var ichorStartTick: Long? = null
    private var ichorActive = false
    private var lastIchorTick = 0L
    private var ichorCount = 0
    /** Entity id to the tick it was first seen, for every entity recorded during this Ichor. */
    private val tracked = mutableMapOf<Int, Long>()
    /** Entity ids seen before the recording started, so only entities spawned for the attack get logged. */
    private val known = mutableSetOf<Int>()
    private val seenNames = mutableSetOf<String>()
    private val seenSounds = mutableSetOf<String>()
    private val particleCounts = mutableMapOf<String, Int>()

    private val recording get() = ichorStartTick != null

    override fun init() {
        register<WorldChangeEvent> { stopRecording(log = false) }

        register<TickEvent.Start> {
            ticks ++
            if (mc.player == null || mc.level == null || LocationUtils.world != WorldType.Rift) return@register
            val boss = findBossStand() ?: return@register

            val ichor = standsAround(boss).firstNotNullOfOrNull { ichorRegex.find(it.name.unformattedText.uppercase()) }
            if (ichor != null) {
                lastIchorTick = ticks
                if (! ichorActive) onIchorStart(boss, ichor.groupValues[1])
            }
            else if (ichorActive && ticks - lastIchorTick > 5) {
                ichorActive = false
                log("&etimer ended&7, boss ${describePos(boss.position())}, you ${dist(player.position())} from boss")
            }

            if (! recording) {
                // Remember what is already around, so only entities that appear for the attack get logged.
                known += entitiesNear(boss).map(Entity::getId)
                return@register
            }

            scanEntities(boss)
            if (! ichorActive && ticks - lastIchorTick > AFTER_TICKS) stopRecording(log = true)
        }

        register<EntityUnloadEvent> {
            val firstSeen = tracked.remove(event.entity.id) ?: return@register
            log("&cremoved&7 ${describe(event.entity)} after ${ticks - firstSeen}t")
        }

        register<ChatMessageEvent> {
            val text = event.unformattedText
            if ("ichor" in text.lowercase() || (recording && "Took" in text)) log("&bchat:&f $text")
        }

        register<MainThreadPacketReceivedEvent.Post> {
            when (val packet = event.packet) {
                is ClientboundSetTitleTextPacket -> logTitle(packet.text.unformattedText)
                is ClientboundSetSubtitleTextPacket -> logTitle(packet.text.unformattedText)
                is ClientboundSoundPacket -> logSound(packet.sound.value().location.path, Vec3(packet.x, packet.y, packet.z))
                is ClientboundSoundEntityPacket -> logSound(packet.sound.value().location.path, mc.level?.getEntity(packet.id)?.position())
                is ClientboundLevelParticlesPacket -> if (recording) {
                    val id = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle.type)?.path ?: "?"
                    particleCounts.merge(id, packet.count.coerceAtLeast(1), Int::plus)
                }
                else -> Unit
            }
        }
    }

    private fun onIchorStart(boss: ArmorStand, seconds: String) {
        ichorActive = true
        if (! recording) {
            ichorStartTick = ticks
            ichorCount ++
            tracked.clear()
            seenNames.clear()
            seenSounds.clear()
            particleCounts.clear()
        }
        log("&etimer started at ${seconds}s &7(#$ichorCount), boss ${describePos(boss.position())}, you ${dist(player.position())} from boss")
    }

    private fun scanEntities(boss: ArmorStand) {
        for (entity in entitiesNear(boss)) {
            if (entity.id in known || entity == player) continue
            if (entity.id !in tracked) {
                tracked[entity.id] = ticks
                log("&anew&7 ${describe(entity)}")
            }
            // Names of tracked entities can count down or change when the attack goes off.
            val name = entity.customName?.unformattedText ?: continue
            if (name.isNotBlank() && seenNames.add("${entity.id}:$name")) log("&7name #${entity.id}: &f$name")
        }
    }

    private fun stopRecording(log: Boolean) {
        if (log && recording) {
            val particles = particleCounts.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key} x${it.value}" }
            log("&erecording done&7, ${tracked.size} entities still there. Particles: ${particles.ifEmpty { "none" }}")
        }
        ichorStartTick = null
        ichorActive = false
        tracked.clear()
        known.clear()
    }

    private fun logTitle(text: String) {
        if (text.isBlank()) return
        if ("ichor" in text.lowercase() || recording) log("&btitle:&f $text")
    }

    private fun logSound(id: String, pos: Vec3?) {
        if (! recording || ! seenSounds.add(id)) return
        log("&bsound:&f $id &7${pos?.let { "at ${dist(it)} from boss" } ?: ""}")
    }

    private fun entitiesNear(boss: ArmorStand) = level.getEntities(boss, boss.boundingBox.inflate(SCAN_RADIUS)) { it != boss }

    private fun describe(entity: Entity): String {
        val type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path
        val name = entity.customName?.unformattedText?.takeIf(String::isNotBlank)?.let { " \"$it\"" } ?: ""
        val head = (entity as? LivingEntity)?.getItemBySlot(EquipmentSlot.HEAD)?.takeUnless { it.isEmpty }
            ?.let { " head=${it.skyblockId.ifEmpty { it.hoverName.string }}" } ?: ""
        val flags = (entity as? ArmorStand)?.let { buildString { if (it.isInvisible) append(" invisible"); if (it.isSmall) append(" small") } } ?: ""
        return "#${entity.id} $type$name$head$flags at ${dist(entity.position())} from boss, ${"%.1f".format(entity.distanceTo(player))} from you"
    }

    private fun dist(pos: Vec3): String {
        val boss = findBossStand() ?: return "?"
        val d = pos.subtract(boss.position())
        return "%.1f (dx %.1f, dy %.1f, dz %.1f)".format(d.length(), d.x, d.y, d.z)
    }

    private fun describePos(pos: Vec3) = "%.1f %.1f %.1f".format(pos.x, pos.y, pos.z)

    private fun log(message: String) {
        val start = ichorStartTick
        ChatUtils.modMessage("&7[Vampire] Ichor${if (start != null) " +${ticks - start}t" else ""}: $message")
    }
}
