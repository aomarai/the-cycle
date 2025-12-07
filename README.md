HardcoreCycle Plugin
=====================

A small Paper plugin to cycle hardcore worlds (create a fresh world, move players, and delete the previous world) and to provide lobby fallback via Bungee/Velocity or a local world.

Key features
- Automatic world cycling with safe deletion (atomic move before recursive delete)
- Config-driven role model: mark a server as `hardcore` (creates/deletes worlds) or `lobby` (does not create/delete)
- Bungee-forward RPC so lobby instances can request the hardcore backend to perform admin actions (secure with an optional shared secret)
- Webhook notifications after cycles
- Scoreboard, bossbar and actionbar features

Installation
------------
1. Build the plugin JAR (`the-cycle-1.0.0.jar`) with Maven: `mvn -DskipTests=false package`.
2. Copy the JAR to the `plugins/` directory on your backend Paper servers:
   - Hardcore server(s): `C:\path\to\paper-hardcore\plugins\the-cycle.jar`
   - Lobby server (optional): `C:\path\to\paper-lobby\plugins\the-cycle.jar`
3. Do NOT install the JAR on your proxy (BungeeCord/Velocity). The plugin uses the proxy only via outgoing/incoming plugin messages.
4. Start/restart the servers.

Configuration
-------------
File: `plugins/HardcoreCycle/config.yml`

Important keys:
- `server.role` — `hardcore` or `lobby`. On `hardcore` the plugin creates/deletes worlds and handles RPCs. On `lobby` it does not create/delete worlds and will forward admin requests to the configured hardcore backend.
- `server.hardcore` — (optional) the proxy server name of your hardcore backend (used by lobby instances when forwarding RPCs via Bungee Forward).
- `server.rpc_secret` — (optional) shared secret. When set, forwarded RPCs must include this secret.
- `lobby.server` — proxy server name of the lobby (if set, plugin will try to `Connect` players there when the hardcore world is unavailable).
- `lobby.world` — local world name to teleport players to as a fallback when `lobby.server` is not used.
- `behavior.delete_previous_worlds` — whether to delete previous worlds (default: true)
- `behavior.defer_delete_until_restart` — record deletion for next restart instead of immediate deletion
- `behavior.async_delete` — perform deletion asynchronously when possible

Example (hardcore backend):

```yaml
server:
  role: "hardcore"
  hardcore: ""       # not used on hardcore
  rpc_secret: "my-secret-if-you-want"

lobby:
  server: "hub1"
  world: ""

behavior:
  delete_previous_worlds: true
  defer_delete_until_restart: false
  async_delete: true
```

Example (lobby backend):

```yaml
server:
  role: "lobby"
  hardcore: "hardcore1"    # proxy name of the hardcore server
  rpc_secret: "my-secret-if-you-want"

lobby:
  server: ""
  world: "lobby_world"
```

How it works (lobby + hardcore)
-------------------------------
- The hardcore server (role=`hardcore`) is the only instance that will create and delete world folders. When a cycle is triggered it creates a new world named `hardcore_cycle_<n>`.
- If the new world spawn is unavailable or world creation fails, players are sent to the configured lobby:
  - If `lobby.server` is set and Bungee is configured, the plugin sends a Bungee `Connect` message for each player to the proxy lobby server.
  - Else, if `lobby.world` is set on the same backend, the player is teleported to that local world.
- Lobby instances (role=`lobby`) host the plugin for scoreboard/bossbar and optionally forward admin requests (like `/cycle cycle-now`) to the hardcore backend via a plugin-message RPC. Forwarding uses the `BungeeCord` `Forward` subchannel to route a message to the hardcore backend and deliver it on the channel `TheCycleRPC`.
- Use `server.rpc_secret` to configure a shared secret for forwarded RPCs — the hardcore backend will validate this secret.

BungeeCord setup notes
----------------------
- Proxy config must define backend servers with names matching `server.hardcore` and your lobby server name.
- Ensure `ip_forward: true` in the proxy and `bungeecord: true` / `settings.bungeecord` on the backend spigot/paper servers.
- The plugin registers the outgoing `BungeeCord` plugin channel and uses it for both `Connect` and `Forward` messages.

Ports and RAM (recommended for 6-8 players)
-------------------------------------------
- Lobby server: lightweight; 512MB–1GB RAM is sufficient for a purely lobby proxy server with few plugins.
- Hardcore server: 1GB–2GB RAM should be adequate for a small 6–8 player hardcore world, depending on plugins/mods and view distance.
- Common ports: backend servers can use any available port; typical patterns:
  - Bungee Proxy: 25565 (public)
  - Lobby backend: 25566
  - Hardcore backend(s): 25567 (and upwards for more backends)
  These are suggestions — pick unused ports in your environment.

Installation path
-----------------
- Place the plugin JAR in each backend Paper server's `plugins/` folder. Do not place the JAR on the proxy.

Commands
--------
- `/cycle setcycle <n>` — set the cycle number
- `/cycle cycle-now` — trigger an immediate cycle
  - On lobby servers this will forward the request to the configured hardcore backend (if `server.hardcore` is set). Use `server.role` to control behavior.
- `/cycle status` — show cycle number and players online

Security and deletion safety
----------------------------
- The plugin refuses to delete folders outside the server's world container by using canonical paths and ensuring the target path starts with the server world container path.
- World deletion first attempts an atomic move to a temporary folder named `<world>.deleting.<uuid>` and then deletes the moved folder recursively. This reduces the chance of partial deletions on interruptions.

Testing
-------
- Unit tests are included under `src/test/java`. Run with Maven:

```powershell
mvn -DskipTests=false test
```

Files in repo
-------------
- `src/main/java/dev/wibbleh/the_cycle/*` — plugin sources
- `src/test/java/dev/wibbleh/the_cycle/*` — unit tests
- `src/main/resources/config.yml` — default configuration
- `src/main/resources/plugin.yml` — plugin metadata and command registration

Support / Contribution
----------------------
If you'd like additional features (for example, authentication for forwarded requests, richer RPC with response delivery, or monitoring hooks), open an issue or provide a PR.


