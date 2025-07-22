# MasterCombat

[![download](https://img.shields.io/github/downloads/OPmasterLEO/MasterCombat/total?style=for-the-badge)](https://github.com/OPmasterLEO/MasterCombat/releases)
![license](https://img.shields.io/github/license/OPmasterLEO/MasterCombat?style=for-the-badge)
![stars](https://img.shields.io/github/stars/OPmasterLEO/MasterCombat?style=for-the-badge)
![forks](https://img.shields.io/github/forks/OPmasterLEO/MasterCombat?style=for-the-badge)

<hr>

## About

MasterCombat is a modern Minecraft combat plugin inspired by DonutSMP and Master SMP (mastersmc.net), developed by OPmasterLEO.  
It features advanced combat tagging, PvP protection for new players, action bar timers, WorldGuard region support, and more.

- **Combat Tagging**: Players are tagged when they deal or receive real damage (not just hits).
- **PvP Protection**: Newbie protection system prevents new players from being attacked for a configurable time.
- **Action Bar Timer**: Shows remaining combat time in the action bar.
- **WorldGuard Support**: Honors PvP-denied regions.
- **Folia Support**: Fully compatible with Folia's async scheduling.
- **End Crystal, TNT, Respawn Anchor, Bed, Pet, Projectile, and Fishing Rod linking**: Tags correct attacker for explosions and indirect damage.
- **Glowing Indicator**: Optional glowing effect for tagged players.
- **Command Blocking**: Prevents usage of specified commands while in combat.
- **Update System**: Checks for updates and can auto-download new versions.

Any issues or suggestions should be reported in the [Issues tab](https://github.com/OPmasterLEO/MasterCombat/issues).  
You are free to DM me on Discord (`leqop`)!

## Installation

1. Download the latest jar from [GitHub Releases](https://github.com/OPmasterLEO/MasterCombat/releases/latest)
2. Place `MasterCombat-v<version>.jar` in your server's `plugins` folder
3. Restart your server (do not use `/reload`)

## Updating

1. Run `/combat update` in-game or from console to check for updates
2. If an update is found, run `/combat update` again to auto-download the new jar to the `update` folder
3. Restart your server to apply the update
4. (Optional) Delete your old `config.yml` to regenerate defaults

## Configuration

- All settings are in `plugins/MasterCombat/config.yml`
- Customize combat duration, protection time, blocked commands, glowing, and more
- Supports PlaceholderAPI for messages

## Commands

- `/combat reload` — Reloads the plugin configuration
- `/combat toggle` — Enables/disables combat tagging
- `/combat update` — Checks for and downloads plugin updates
- `/combat api` — Shows API status
- `/combat protection` — Shows your PvP protection time left
- `NewbieProtection.settings.disableCommand` — Disables your newbie PvP protection (default: `/removeprotect`)

## Permissions

- `combat.admin` — Access to admin commands
- `combat.protection` — Access to protection commands

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 5.x.x   | :white_check_mark: |
| 4.x.x   | :white_check_mark: |
| < 4.0   | :x:                |

## Security Policy

If you discover a vulnerability, please report it via GitHub Issues or DM on Discord.  
You will receive updates on your report and fixes will be prioritized for supported versions.

<hr>

![bStats](https://bstats.org/signatures/bukkit/MasterCombatX.svg)
