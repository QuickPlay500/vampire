# Vampire

A [NoammAddons](https://github.com/Noamm9/NoammAddons) addon for the Hypixel Skyblock Vampire Slayer (Riftstalker Bloodfiend). It handles the boss mechanics for you and shows titles. Moving and hitting the boss with your sword is still up to you.

Works on Minecraft 26.2 and 26.3 with Fabric.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) (0.19.5 or newer) for Minecraft 26.2 or 26.3.
2. Put these mods into your `mods` folder:
   - **NoammAddons, cheat version**: [latest release on GitHub](https://github.com/Noamm9/NoammAddons/releases/latest). This addon is built against NoammAddons 1.3.5.
   - **Fabric API**: [Modrinth](https://modrinth.com/mod/fabric-api)
   - **Fabric Language Kotlin**: [Modrinth](https://modrinth.com/mod/fabric-language-kotlin)
3. The features are in the NoammAddons settings under the **Vampire** category.

## Features

### Auto Slayer

Automates the boss mechanics.

- **Auto Healing Melon**: uses the Healing Melon from your hotbar when your health is at or below a set number of hearts. **Melon Cooldown** sets the minimum time between two uses.
- **Auto Holy Ice**: uses Holy Ice when the TWINCLAWS timer on the boss reaches a set value (**Ice At**). The hit lands at 0.0s and Holy Ice lasts 1.5s. If another action is running, the ice waits for it instead of being skipped.
- **Auto Impel**: does the boss' orders for you, once per order. Back-to-back orders on higher tiers are queued.
  - **CLICK UP / CLICK DOWN**: looks up or down, right-clicks, and turns back to where you were looking. **Rotation Time** sets how smooth the turn is; 0 snaps instantly.
  - **JUMP**: presses jump. With **Jump Failsafe**, if you're in the air it waits until you land, because a jump in mid-air doesn't count.
  - **SNEAK**: presses sneak. With **Sneak Failsafe**, if you're already sneaking it unsneaks and sneaks again so it counts as a new sneak.
  - **Impel Delay**: ticks to wait before reacting to an order.
- **Auto Steak Stake**: switches to your Steak Stake once the boss is low enough to be staked. Works with the Steak Stake and the Super Sharp 'n Stabby Steak Stake.

### Vampire Notifications

Shows a title on screen for:

- **Boss Spawned** and **Boss Dead**
- **Twinclaws**: optionally only while Mania is active.
- **Stonewrath**: the boss flies up and you need to go to the stones.
- **Ready**: the boss has locked onto your position and you need to leave the stones (3s after it flew up).
- **Steak Stake**: the boss can be staked.

### Debug

Turn this on to print titles, boss nametags and what Auto Slayer does (Impel orders, Holy Ice, stake swaps) to chat. Useful if something doesn't trigger.

## Build

You need JDK 25.

```bash
./gradlew buildAll
```

The jars end up in `build/libs/`, one per Minecraft version. In IntelliJ you can also use the `Vampire [buildAll]`, `Vampire [runClient 26.2]` and `Vampire [runClient 26.3]` run configs from `.run/`.

Based on the [Noamm-Squared](https://github.com/Noamm9/Noamm-Squared) addon template.
