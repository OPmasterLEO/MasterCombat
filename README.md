# MasterCombat

[![download](https://img.shields.io/github/downloads/OPmasterLEO/MasterCombat/total?style=for-the-badge)](https://github.com/OPmasterLEO/MasterCombat/releases)
![license](https://img.shields.io/github/license/OPmasterLEO/MasterCombat?style=for-the-badge)
![stars](https://img.shields.io/github/stars/OPmasterLEO/MasterCombat?style=for-the-badge)
![forks](https://img.shields.io/github/forks/OPmasterLEO/MasterCombat?style=for-the-badge)
[![jitpack](https://jitpack.io/v/OPmasterLEO/MasterCombat.svg)](https://jitpack.io/#OPmasterLEO/MasterCombat)

<hr>

## 🎮 About

MasterCombat is a modern Minecraft combat plugin inspired by DonutSMP and made for MasterSMP (mastersmp.net), developed by OPmasterLEO.  
It features advanced combat tagging, PvP protection for new players, action bar timers, WorldGuard region support, and more.

### ⚔️ Core Features

| Feature | Description |
|---------|-------------|
| 🏷️ **Combat Tagging** | Smart tagging system based on real damage, not just hits |
| 🛡️ **PvP Protection** | Configurable newbie protection system |
| ⏱️ **Action Bar Timer** | Visual combat duration display |
| 🌍 **WorldGuard Support** | Respects PvP-denied regions |
| ⚡ **Folia Support** | Full async scheduling compatibility |
| 💥 **Advanced Damage Linking** | Accurate attacker tracking for:<br>• End Crystal<br>• TNT<br>• Respawn Anchor<br>• Bed<br>• Pet<br>• Projectile<br>• Fishing Rod |
| ✨ **Glowing Indicator** | Visual effect for tagged players |
| 🚫 **Command Blocking** | Customizable command restrictions |
| 🔄 **Update System** | Automatic updates with download support

Any issues or suggestions should be reported in the [Issues tab](https://github.com/OPmasterLEO/MasterCombat/issues).  
You are free to DM me on Discord (`opmasterleo`)!

## 📥 Installation

> 🚀 Quick Start Guide

1. 📦 Download latest jar from [GitHub Releases](https://github.com/OPmasterLEO/MasterCombat/releases/latest)
2. 📁 Place `MasterCombat-v<version>.jar` in your server's `plugins` folder
3. 🔄 Restart your server ⚠️ Do not use `/reload`

## 🔄 Updating

| Step | Action |
|------|--------|
| 1️⃣ | Run `/combat update` to check for updates |
| 2️⃣ | If available, run command again to download |
| 3️⃣ | Restart server to apply update |
| 4️⃣ | *(Optional)* Delete old `config.yml` for defaults |

> 💡 Updates can be checked from both in-game and console

## ⚙️ Configuration

```yaml
📁 Location: plugins/MasterCombat/config.yml

🔧 Customizable Features:
├── ⏱️ Combat duration
├── 🛡️ Protection time
├── 🚫 Blocked commands
├── ✨ Glowing effects
└── 📝 Messages (PlaceholderAPI support)
```

## 🎮 Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/combat reload` | 🔄 Reload configuration | `combat.admin` |
| `/combat toggle` | 🔀 Toggle combat tagging | `combat.admin` |
| `/combat update` | 📥 Check/download updates | `combat.admin` |
| `/combat api` | 📊 View API status | `combat.admin` |
| `/combat protection` | 🛡️ Check protection time | `combat.protection` |
| `/removeprotect` | 🚫 Disable newbie protection | `combat.protection` |

> 💡 Command names are configurable in settings

## 🔑 Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `combat.admin` | 👑 Administrative access | `op` |
| `combat.protection` | 🛡️ Protection commands | `true` |

> 💡 Permissions can be managed with any permission plugin

## Supported Versions

### 🔧 Server Compatibility

This plugin features advanced multi-threading support across all major Minecraft server platforms:

#### Modern Platforms
| Platform | Version | Key Features |
|----------|---------|--------------|
| **Paper** | 1.16.5+ | ✨ Native async scheduler<br>⚡ Multi-threaded worker pool |
| **Folia** | Latest | 🌍 Region-aware scheduling<br>⚡ Native async support |
| **Canvas** | Latest | 🌍 Region-aware tasks<br>⚡ Async capabilities |

#### Legacy Support
| Platform | Version | Key Features |
|----------|---------|--------------|
| **Legacy Paper** | Pre-1.16.5 | 🔄 Custom thread pool<br>⚙️ Legacy task compatibility |
| **Modern Spigot** | 1.14+ | ⚡ Async scheduling<br>🔄 Multi-thread support |
| **Legacy Spigot** | Pre-1.14 | 🔄 Custom thread pool<br>⚙️ Backward compatibility |

#### Special Platform Support
| Platform | Features |
|----------|-----------|
| **ArcLight** | 🔒 ClassLoader-aware execution<br>⚡ Specialized task handling |

#### 💫 Advanced Threading Features
- **Adaptive Threading**: Auto-scales worker pool (2-16 threads) based on CPU cores
- **Smart Scheduling**: Automatic platform detection for optimal task distribution
- **Region Awareness**: Enhanced performance with Folia/Canvas region support
- **Legacy Support**: Seamless operation on older server versions
- **Custom Pooling**: Dedicated thread management for legacy platforms

> 📝 For detailed compatibility information and latest updates, check our [GitHub Releases](https://github.com/OPmasterLEO/MasterCombat/releases) page.

## 🔒 Security Policy

### Reporting a Vulnerability

| Method | Contact |
|--------|---------|
| 🐛 **GitHub Issues** | [Create Issue](https://github.com/OPmasterLEO/MasterCombat/issues) |
| 💬 **Discord** | DM `opmasterleo` |

> ⚡ Quick Response Guarantee: All security reports receive priority attention
> 
> 🛡️ Supported Versions: Security fixes are backported to maintained releases

<hr>

![bStats](https://bstats.org/signatures/bukkit/MasterCombatX.svg)
