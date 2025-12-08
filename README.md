# The Cycle - Hardcore Minecraft Plugin

[![Java CI with Maven](https://github.com/aomarai/the-cycle/actions/workflows/maven-test.yml/badge.svg)](https://github.com/aomarai/the-cycle/actions/workflows/maven-test.yml)
[![codecov](https://codecov.io/gh/aomarai/the-cycle/branch/main/graph/badge.svg)](https://codecov.io/gh/aomarai/the-cycle)

A Minecraft server plugin that manages hardcore world cycling by generating fresh worlds, safely moving players to a lobby during generation, and returning them when ready.

## Features

- **Automatic World Cycling** - Triggers new world generation on player death
- **Safe Player Management** - Moves players to lobby during world generation
- **Dragon Tracking** - Tracks ender dragon kills and attempts between victories
- **Statistics Display** - Scoreboard shows cycle number, attempts, and total wins
- **Dramatic Titles** - Large title screens for cycle starts and dragon defeats
- **Mid-Cycle Protection** - Players joining during active cycles wait in lobby
- **Auto-Start Cycles** - Configurable automatic cycle initiation when players join
- **Dual RPC System** - BungeeCord plugin messaging + HTTP fallback
- **Persistent Queue** - Failed RPCs survive server restarts
- **Health Monitoring** - HTTP endpoint for status checks

## Quick Start

### Installation

1. Download `the-cycle-1.0.0.jar`
2. Copy to the `plugins/` folder on **both** lobby and hardcore servers
3. Restart both servers
4. Configure `plugins/TheCycle/config.yml` on each server
5. Set `server.role` to either `lobby` or `hardcore`

### Why Install on Both Servers?

- **Hardcore Server** (`role: hardcore`) - Creates/deletes worlds, manages game state
- **Lobby Server** (`role: lobby`) - Forwards cycle requests, manages player transfers

## Configuration

### Basic Configuration

Edit `plugins/TheCycle/config.yml`:

```yaml
server:
  role: "lobby"                 # "lobby" or "hardcore"
  hardcore: "hardcore"          # Proxy server name for hardcore
  rpc_secret: "your-secret"     # Shared secret for RPC authentication
  http_enabled: true            # Enable HTTP RPC server
  http_port: 8080
  http_bind: "0.0.0.0"
  hardcore_http_url: "http://hardcore-ip:8080/rpc"  # For lobby
  lobby_http_url: "http://lobby-ip:8080/rpc"        # For hardcore

behavior:
  delete_previous_worlds: true
  defer_delete_until_restart: false
  async_delete: true
  shared_death: true             # One death triggers cycle for all
  countdown_send_to_lobby_seconds: 10
  countdown_send_to_hardcore_seconds: 10
  countdown_broadcast_to_all: true
  delay_before_generation_seconds: 3
  wait_for_players_to_leave_seconds: 30
  auto_start_cycles: true        # Auto-start when players join lobby

features:
  scoreboard: true               # Display cycle stats
  actionbar: true
  bossbar: true

webhook:
  url: ""                        # Optional Discord webhook URL
```

### Key Configuration Notes

- **server.role** - Set to `hardcore` on the backend server, `lobby` on the fallback server
- **server.hardcore** - Must match the server name in your proxy config (BungeeCord/Velocity)
- **rpc_secret** - Must be identical on both servers; used for HMAC authentication
- **http_enabled** - Recommended when servers are on different machines
- **auto_start_cycles** - Set to `false` for manual cycle control

## Configuration Examples

### Lobby Server (HTTP-based)

```yaml
server:
  role: "lobby"
  hardcore: "hardcore"
  rpc_secret: "bighardcoreworld"
  http_enabled: true
  http_port: 8080
  hardcore_http_url: "http://10.0.0.2:8080/rpc"

lobby:
  server: ""
  world: "Lobby"
```

### Hardcore Server

```yaml
server:
  role: "hardcore"
  rpc_secret: "bighardcoreworld"
  http_enabled: true
  http_port: 8080
  lobby_http_url: "http://10.0.0.3:8080/rpc"

behavior:
  countdown_send_to_lobby_seconds: 8
  delay_before_generation_seconds: 3
  wait_for_players_to_leave_seconds: 30
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/cycle cycle-now` | Request immediate world cycle | `thecycle.cycle` |
| `/cycle setcycle <n>` | Set the current cycle number | `thecycle.cycle` |
| `/cycle status` | Display current cycle and player counts | None |

## How It Works

1. **Cycle Triggered** - Player death or `/cycle cycle-now` command
2. **Players Moved** - Active players teleported to lobby with countdown
3. **World Cleared** - Plugin waits for players to leave (configurable timeout)
4. **World Generated** - New world created on main thread
5. **Notification Sent** - Hardcore notifies lobby that world is ready
6. **Players Returned** - Lobby moves players to new hardcore world
7. **Old World Deleted** - Previous world deleted (async or deferred)

## Proxy Setup (BungeeCord/Velocity)

- Enable `ip_forward` in your proxy configuration
- Ensure server names in proxy config match `server.hardcore` and `server.lobby` settings
- Plugin uses BungeeCord plugin messaging channel for RPC forwarding
- HTTP RPC serves as fallback when plugin messaging is unavailable

## HTTP RPC

### Endpoints

**POST /rpc** - RPC endpoint for inter-server communication
- Requires `X-Signature` header with HMAC-SHA256 authentication
- Used for cycle requests and world-ready notifications

**GET /health** - Health check endpoint
```json
{
  "status": "ok",
  "role": "hardcore",
  "cycleNumber": 42,
  "playersOnline": 6
}
```

### Use Cases for Health Endpoint

- Monitor server health from external tools (Prometheus, Nagios, etc.)
- Verify RPC connectivity before operations
- Build status dashboards
- Troubleshoot connection issues

### Reliability Features

1. **Automatic Retry** - HTTP RPC uses exponential backoff with jitter (up to 3 attempts)
2. **Persistent Queue** - Failed RPCs saved to `failed_rpcs.json` and retried every 60 seconds
3. **Message Expiry** - Messages older than 24 hours automatically cleaned up
4. **Configuration Validation** - Invalid configs caught on startup with detailed error messages

## Server Requirements

### Recommended Resources (6-8 players)

- **Lobby Server**: 512 MB - 1 GB RAM (lightweight, always-on)
- **Hardcore Server**: 2-3 GB RAM (world generation, chunk loading)

### Ports

- **Minecraft**: Default 25565 (proxy) 
- **Backend Servers**: 25566 (lobby), 25567 (hardcore) - or configure as needed
- **HTTP RPC**: 8080 (configurable)

## Troubleshooting

### Players Not Moving to Lobby

**Check:**
- `lobby.server` matches proxy server name
- Proxy has `ip_forward` enabled
- Plugin channel registration (check logs for warnings)
- HTTP RPC fallback configured if plugin messaging fails

**Test:**
```bash
curl http://hardcore-server:8080/health
# Should return JSON status
```

### Lobby Doesn't Trigger Hardcore Cycle

**Check:**
- HTTP RPC endpoint enabled and reachable on hardcore server
- `rpc_secret` matches on both servers
- Firewall allows traffic on HTTP port
- Review logs for RPC retry attempts

**Test health endpoint:**
```bash
curl http://hardcore-server:8080/health
```

### World Generation Fails or Stalls

**Possible causes:**
- Insufficient CPU or memory
- Players didn't leave world before timeout
- Heavy chunk processing

**Solutions:**
- Increase `wait_for_players_to_leave_seconds`
- Allocate more RAM to hardcore server
- Check server logs for errors

### Configuration Errors on Startup

The plugin validates configuration and logs detailed errors. Common issues:
- Invalid URLs (must start with `http://` or `https://`)
- Missing required fields for server role
- Invalid port numbers (must be 1-65535)
- Empty or short RPC secrets (warning only)

Fix reported issues and restart the server.

### HTTP RPC Failures

**Check logs for:**
- Retry attempts with exponential backoff
- Failed RPC queue persistence
- Network connectivity issues

**Verify:**
- Firewall rules allow traffic on HTTP port
- URLs are correct in configuration
- Health endpoint responds: `curl http://server:8080/health`

## Development

### Building from Source

```bash
# Prerequisites: Java 21, Maven 3.6+
git clone https://github.com/aomarai/the-cycle.git
cd the-cycle
mvn clean package

# Output: target/the-cycle-1.0.0.jar
```

### Running Tests

```bash
mvn test
# All 121+ tests should pass
```

### Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines, code style, testing requirements, and pull request process.

## License

This plugin is provided as-is for use and modification. See repository for details.

## Support

For issues, configuration help, or bug reports:
1. Check this README and troubleshooting section
2. Review server logs for error messages
3. Open an issue on GitHub with:
   - Server version (Java, Paper, plugin)
   - Configuration file
   - Relevant log excerpts
   - Steps to reproduce
