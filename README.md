# HardcoreCycle Plugin

This plugin manages cyclical hardcore worlds: it creates a new world for each cycle, moves players between a small always-on lobby server and the hardcore server, and optionally deletes old worlds. It supports forwarding RPCs between a lobby and hardcore backend using either BungeeCord plugin messaging or HTTP calls.

## Quick install

1. Build the plugin with Maven:

```bash
mvn clean package
```

2. Copy `target/the-cycle-1.0.0.jar` to the `plugins/` folder of both your lobby and hardcore servers.

3. Configure your `config.yml` (see below).

4. If using BungeeCord/Velocity, register both servers in your proxy and ensure `BungeeCord` plugin messaging is enabled.

5. Start the lobby and hardcore servers.

## Configuration

All configuration options live in `plugins/HardcoreCycle/config.yml` (created from the default bundled config). Key values:

- `server.role`: `hardcore` or `lobby`. The hardcore instance creates/deletes worlds.
- `server.hardcore`: (on lobby) your proxy name for the hardcore server.
- `server.rpc_secret`: shared HMAC secret used for HTTP RPCs.
- `server.http_enabled`: whether to start an embedded HTTP receiver for RPCs.
- `server.http_port` and `server.http_bind`: bind settings for the embedded HTTP server.
- `server.hardcore_http_url`: (optional) full URL to post RPCs to the hardcore backend.
- `server.lobby_http_url`: (optional) full URL to post world-ready notifications to the lobby.
- `server.randomize_seed` (default `true`): when `true`, each new hardcore world receives a new random seed.
- `server.seed` (default `0`): if `randomize_seed` is `false` and this is non-zero, the configured seed will be used for world creation.
- `server.properties_path` (default `"server.properties"`): path to the server.properties file. The plugin automatically updates the `level-name` and `level-seed` properties.
- `behavior.restart_on_cycle` (default `true`): when `true`, the server restarts after each cycle to properly load the new world. **Recommended for reliable world cycling.**
- `lobby.server` and `lobby.world`: where to send players when the hardcore world is unavailable.

### Important: Server Restart Behavior

**NEW:** By default (`behavior.restart_on_cycle: true`), the plugin now restarts the hardcore server after each cycle. This ensures:
- The new world is properly loaded as Minecraft's main world
- Random seeds are correctly applied
- World generation is reliable even when the default world has been deleted
- Players are automatically moved back to the hardcore server after it restarts

**How it works:**
1. Cycle is triggered (via `/cycle` command)
2. Players are moved to the lobby server
3. `server.properties` is updated with the new world name and seed
4. Hardcore server restarts
5. On startup, Minecraft generates/loads the new world as the main world
6. Lobby server is notified that the world is ready
7. Players are automatically moved back to the hardcore server

**Legacy mode** (`behavior.restart_on_cycle: false`): The server attempts to create worlds in-memory without restarting. This may not work correctly if the default world has been deleted.

### Server Properties Integration

The plugin automatically updates your `server.properties` file to set:
- `level-name` to the current cycle world (e.g., `hardcore_cycle_1`, `hardcore_cycle_2`)
- `level-seed` to the generated random seed (when `randomize_seed: true`)

A backup (`server.properties.backup`) is created before each modification.

### Safety
- World deletion is constrained to the server's world folder; the plugin will not delete paths outside the server directory.
- Deletion can be asynchronous or deferred until restart. See `behavior.defer_delete_until_restart` and `behavior.async_delete`.
- Server restarts are graceful with player notifications.

## Usage

- `/cycle` or `/cycle cycle-now` — Trigger a new cycle. On lobby instances this forwards the request to the hardcore backend.

Behavior (with `restart_on_cycle: true`):
1. Players are moved to the lobby server
2. `server.properties` is updated
3. Hardcore server restarts
4. New world is generated on restart
5. Players are automatically moved back to hardcore

## BungeeCord notes

- The plugin prefers HTTP RPC forwarding if `server.hardcore_http_url` is configured. HTTP does not require a player to send messages.
- If HTTP is not configured, the plugin falls back to Bungee plugin messaging which requires an online player to send the plugin message through.

## Ports and resources

- Default embedded HTTP port: `8080`. Configure with `server.http_port`.
- Lobby server memory recommendation (6-8 players): 512MB - 1GB (since it's mostly proxy/hub duties).
- Hardcore server memory recommendation (6-8 players, world generation): 2GB - 4GB depending on view-distance and plugins.

## Persistence

- Pending player moves and persistent RPC queue are stored under the plugin data folder (`plugins/HardcoreCycle`). They survive restarts.

## Development

- Run unit tests:

```bash
mvn test
```

If you use a Maven wrapper, run `./mvnw test` (or `mvnw.cmd` on Windows).

## Support

If you see failed world generation with `WorldInitEvent may only be triggered synchronously`, ensure the plugin defers generation to the main server thread (this plugin schedules generation on the server thread).


