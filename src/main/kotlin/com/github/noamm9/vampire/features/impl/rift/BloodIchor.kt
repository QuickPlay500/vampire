package com.github.noamm9.vampire.features.impl.rift

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
import com.github.noamm9.utils.render.world.Render3D.renderBox
import com.github.noamm9.utils.render.world.Render3D.renderTracer
import com.github.noamm9.vampire.utils.VampireBoss
import com.github.noamm9.vampire.utils.VampireBoss.findBossStand
import com.github.noamm9.vampire.utils.VampireBoss.findIchors
import java.awt.Color

/**
 * Warns when a Tier V Blood Ichor spawns and shows where it is: a tracer like the M7 dragon tracer and a
 * beacon-style beam, both through walls.
 */
object BloodIchor: Feature(
    description = "Notification, tracer and beacon beam for the Blood Ichors of your Vampire Slayer boss (Tier V).",
    name = "Blood Ichor",
    toggled = false
) {
    private val notification by ToggleSetting("Notification", true)
        .withDescription("Shows a title when a Blood Ichor spawns.")
    private val tracer by ToggleSetting("Tracer", true)
        .withDescription("Draws a line from your crosshair to the Blood Ichor.")
    private val tracerWidth by SliderSetting("Tracer Width", 2.5f, 1f, 5f, 0.1f).showIf { tracer.value }
    private val beacon by ToggleSetting("Render Beacon", true)
        .withDescription("Draws a beacon-style beam on each Blood Ichor, visible through walls.")
    private val color by ColorSetting("Color", Color(200, 0, 0), false)

    /** Updated every tick, so rendering doesn't scan entities every frame. */
    private var ichors = emptyList<VampireBoss.Ichor>()

    override fun init() {
        register<WorldChangeEvent> { ichors = emptyList() }

        register<TickEvent.Start> {
            if (mc.player == null || mc.level == null || LocationUtils.world != WorldType.Rift) return@register run { ichors = emptyList() }
            val current = findBossStand()?.let(::findIchors) ?: emptyList()
            val known = ichors.map { it.stand.id }.toSet()
            if (notification.value && current.any { it.stand.id !in known }) ChatUtils.showTitle("&4Blood Ichor!", "")
            ichors = current
        }

        register<RenderWorldEvent> {
            for (ichor in ichors) {
                if (ichor.stand.isRemoved) continue
                val pos = ichor.pos
                if (tracer.value) event.ctx.renderTracer(pos, color.value, tracerWidth.value)
                if (beacon.value) {
                    event.ctx.renderBox(pos.x, pos.y - 0.5, pos.z, 0.6, 200, color.value.withAlpha(90), outline = false, phase = true)
                    event.ctx.renderBox(pos.x, pos.y - 0.5, pos.z, 1.0, 1.0, color.value, color.value.withAlpha(60), phase = true)
                }
            }
        }
    }
}
