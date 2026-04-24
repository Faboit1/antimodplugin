# AntiModDetect

**AntiModDetect** is a Paper 1.21.x plugin that invisibly detects cheat clients and suspicious mods on players using three independent methods:

1. **Sign Translation-Key Detection** – places a temporary sign with Minecraft translation keys that only resolve inside specific mods, then reads the client's response.
2. **Client Brand Detection** – reads the `minecraft:brand` plugin channel that every client sends on login. Requires ProtocolLib or Bukkit Messenger fallback.
3. **Plugin Channel Detection** – checks which channels the client registers (e.g. `meteor-client:main`). Requires ProtocolLib for best coverage.

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 21+ |
| Paper | 1.21.x |
| ProtocolLib | 5.x (optional, but recommended) |

> **Note:** The plugin works without ProtocolLib, but brand and channel detection will only work via the Bukkit Messenger fallback, which may miss some packets on certain server builds.

---

## Installation

1. Download `AntiModDetect-1.0.0.jar` from the [Releases](../../releases) page (or the latest workflow artifact).
2. Place the JAR in your Paper server's `plugins/` folder.
3. *(Optional but recommended)* Also place [ProtocolLib](https://github.com/dmulloy2/ProtocolLib/releases) in `plugins/`.
4. Start or restart the server.
5. The default `config.yml` is generated in `plugins/AntiModDetect/`.

---

## Velocity / BungeeCord Setup

When running AntiModDetect behind a **Velocity** (or BungeeCord) proxy, some extra steps are needed to make sure checks work correctly.

### Why doesn't anything happen on join?

Through a proxy the client connection goes:  
`Client → Velocity → Paper`

The `PlayerJoinEvent` fires normally on Paper, but the player may still be in the "loading terrain" phase for a brief moment. By default, `join-check-delay-ticks` is set to **40** (2 seconds), which is usually enough.

If checks are still not triggering, follow the debug steps below.

### Velocity Configuration Checklist

1. **Enable forwarding in Velocity** (`velocity.toml`):
   ```toml
   player-info-forwarding-mode = "modern"
   ```
   (BungeeCord-compatible mode also works, but Modern is preferred.)

2. **Enable forwarding in Paper** (`config/paper-global.yml`):
   ```yaml
   proxies:
     velocity:
       enabled: true
       online-mode: true
       secret: "your-velocity-secret"
   ```
   Make sure the `secret` matches the one in `velocity.toml`.

3. **Set `online-mode: false`** in `server.properties` on your Paper backend (Velocity handles authentication).

4. **Tune the join delay** in `plugins/AntiModDetect/config.yml`:
   ```yaml
   general:
     join-check-delay-ticks: 40   # 2 seconds – good default for Velocity
   ```
   If you also use an auth plugin (AuthMe, etc.) that teleports the player after login, increase this to `100` (5 seconds) or more.

5. **Install ProtocolLib** on your Paper backend for full brand/channel coverage.

---

## Commands

All commands require the `antimoddetect.admin` permission (default: OP).

| Command | Description |
|---|---|
| `/amd check <player>` | Manually run all checks (respects cooldown) |
| `/amd forcecheck <player>` | Force-run all checks immediately (bypasses cooldown) |
| `/amd info <player>` | Show detailed diagnostics for a player |
| `/amd debug [on\|off]` | Toggle verbose debug mode at runtime |
| `/amd whitelist add <player>` | Add player to bypass whitelist |
| `/amd whitelist remove <player>` | Remove player from whitelist |
| `/amd whitelist list` | List all whitelisted entries |
| `/amd strikes <player>` | Show a player's strike count |
| `/amd strikes reset <player>` | Reset a player's strikes |
| `/amd reload` | Reload config.yml |
| `/amd status` | Show plugin status summary |

**Aliases:** `/antimoddetect`, `/amd`, `/antimod`

---

## Permissions

| Permission | Default | Description |
|---|---|---|
| `antimoddetect.admin` | OP | Full access to all admin commands |
| `antimoddetect.notify` | OP | Receive in-game detection alerts |
| `antimoddetect.bypass` | false | Bypass all checks |
| `antimoddetect.whitelist` | OP | Manage the bypass whitelist |
| `antimoddetect.check` | OP | Manually trigger checks |
| `antimoddetect.reload` | OP | Reload configuration |

---

## Debugging & Troubleshooting

### Step 1 – Enable debug mode

Run `/amd debug on` in-game (or set `general.debug: true` in `config.yml` and `/amd reload`).

Debug mode prints `[AMD-DEBUG]` lines to the console for every decision the plugin makes on join.

### Step 2 – Check player info

After enabling debug, run:
```
/amd info <player>
```

This shows:
- Whether the player is **exempt** from checks (bypass permission, whitelist, exempt gamemode/world)
- Whether the **cooldown** is active (use `/amd forcecheck <player>` to bypass)
- The current **sign session state** (which batch is running, retry count, sign location)
- Plugin config summary (check-on-join, join delay, ProtocolLib status)

### Step 3 – Force a check

```
/amd forcecheck <player>
```

This clears the cooldown and immediately triggers all checks, regardless of any config flags. Watch the console for `[AMD-DEBUG]` output to see exactly what happens.

### Common problems

| Symptom | Likely cause | Fix |
|---|---|---|
| Nothing happens on join | `check-on-join: false` | Set to `true` in config.yml |
| Nothing happens on join | Player has `antimoddetect.bypass` | Remove the permission or use `/amd forcecheck` |
| Nothing happens on join | Player is whitelisted | `/amd whitelist remove <player>` |
| Nothing happens on join | Recheck cooldown active | `/amd forcecheck <player>` or reduce `recheck-cooldown-seconds` |
| Nothing happens on join | `join-check-delay-ticks` too short (Velocity) | Increase to 40–60 in config.yml |
| Sign editor never closes | Client behind high-latency proxy | Increase `editor-open-delay-ticks` to 5+ |
| Brand/channel not detected | ProtocolLib not installed | Install ProtocolLib on the Paper backend |
| Brand/channel not detected | Velocity modifying brand packets | ProtocolLib reads raw packets before Velocity, should be fine |
| False positives | Translation key exists in vanilla | Set `confidence: HEURISTIC` for that key |
| Sign appears for a moment | Normal behaviour – it's invisible to other players | Expected |

### Sign check retries

The sign check automatically retries if the client doesn't respond (e.g. still loading terrain). By default it retries every **10 ticks** (0.5 s) for up to **30 attempts** (15 seconds total). If the client is completely unresponsive (e.g. frozen loading screen), the check gives up silently — this is intentional, since silence is not proof of a mod.

---

## Configuration

The default `config.yml` is generated on first start. Key settings:

```yaml
general:
  debug: false                  # Enable verbose logging
  check-on-join: true           # Auto-check when players join
  join-check-delay-ticks: 40    # 2 s delay (increase for Velocity/AuthMe)
  recheck-cooldown-seconds: 300 # Don't recheck same player within 5 min

sign-detection:
  enabled: true
  editor-open-delay-ticks: 2    # Increase to 5+ for high-latency Velocity
  retry-interval-ticks: 10      # Retry every 0.5 s if client doesn't respond
  max-retries: 30               # Give up after 15 s

brand-detection:
  enabled: true                 # Requires ProtocolLib or Bukkit Messenger

channel-detection:
  enabled: true                 # Requires ProtocolLib or Bukkit Messenger
```

---

## Building from source

```bash
# Requires Java 21 and Maven
mvn clean package -B
# Output: target/AntiModDetect-1.0.0.jar
```

---

## License

MIT
