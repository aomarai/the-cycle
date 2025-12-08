HardcoreCycle Plugin
====================

A small Minecraft server plugin that cycles a hardcore world by generating a fresh world, safely moving players to a small lobby while generation runs and returning players once the new world is ready.

This README covers installation, configuration, running with a proxy (BungeeCord/Velocity), and the plugin's behaviour and trouble‑shooting tips.

Quick summary
- Install the plugin JAR into the `plugins/` folder of both your *lobby* and *hardcore* servers (configured role determines behavior).
- Configure `config.yml` in each server using the `server.role` option (`hardcore` or `lobby`).
- The lobby forwards cycle requests to the hardcore backend via either the proxy plugin channel (BungeeCord) or HTTP RPC.
- The hardcore backend moves players out of the previous world before generating the next world, with a configurable delay and an optional polling timeout to ensure players have left.

Installation
------------
1. Copy `the-cycle-1.0.0.jar` into the `plugins/` folder of both servers (lobby and hardcore).
2. Ensure the plugin JAR is present on each server and restart each server once.

Why install on both servers?
- Hardcore server (role: `hardcore`) — this instance will create and delete worlds and is the authoritative backend.
- Lobby server (role: `lobby`) — this instance forwards `/cycle` requests to the hardcore backend (via proxy or HTTP), accepts world-ready notifications and moves players when the hardcore world is ready, and may show stats or UI for the lobby.

Configuration
-------------
The plugin reads `config.yml` from its data folder. Relevant keys (example sections):

server:
  role: "lobby"                # `hardcore` or `lobby`
  hardcore: "hardcore"         # proxy server name for hardcore (used on lobby)
  rpc_secret: "bighardcoreworld"
  http_enabled: true            # enable embedded HTTP RPC server (useful for HTTP-based forwarding)
  http_port: 8080
  http_bind: "0.0.0.0"
  hardcore_http_url: "http://<HARDCORE_HOST_OR_IP>:8080/rpc"  # optional: lobby sends HTTP RPC to hardcore
  lobby_http_url: "http://<LOBBY_HOST_OR_IP>:8080/rpc"      # optional: hardcore notifies lobby

behavior:
  delete_previous_worlds: true
  defer_delete_until_restart: false
  async_delete: true
  shared_death: true
  countdown_send_to_lobby_seconds: 10
  countdown_send_to_hardcore_seconds: 10
  countdown_broadcast_to_all: true
  delay_before_generation_seconds: 3
  wait_for_players_to_leave_seconds: 30

features:
  scoreboard: true
  actionbar: true
  bossbar: true

webhook:
  url: ""   # Discord/webhook URL for cycle notifications (optional)

Key configuration notes
- `server.role` — set to `hardcore` on the server that should create/delete worlds and to `lobby` on the small always-on server used as a fallback.
- `server.hardcore` on lobby instances must match the `servers` name defined in your proxy (Bungee/Velocity) to allow forwarding via plugin messaging.
- `rpc_secret` — shared secret used for HMAC signing when using the HTTP RPC features. Keep it identical on lobby and hardcore.
- `http_enabled` and `http_port` — when enabled the plugin starts a tiny embedded HTTP server and supports POST /rpc for simple RPCs. This is optional but recommended when the lobby and hardcore are on different machines.

Behavior & flow
---------------
1. A `/cycle cycle-now` command on the lobby forwards the request to the hardcore backend (via Bungee plugin messaging or the configured HTTP RPC URL). The lobby will then wait for the hardcore backend to notify when the new world is ready.
2. On the hardcore backend, when a cycle is triggered:
   - The plugin first schedules a countdown (configurable) and moves players currently inside the *previous* hardcore world to the configured lobby. Dead players are marked and moved on respawn.
   - A short configured delay (`behavior.delay_before_generation_seconds`) is observed, then the plugin polls up to `behavior.wait_for_players_to_leave_seconds` to ensure the previous world is empty before generating the new world. If the timeout is reached the plugin proceeds anyway (best-effort unload and schedule deletion).
   - The plugin generates the new world on the main thread (this is a blocking operation in Bukkit/Paper). When generation completes the hardcore notifies the lobby using HTTP (if configured) and the lobby starts its countdown before moving players to the new hardcore world.
3. All player transfers attempt proxy routing via BungeeCord (plugin messaging) if configured. If proxy routing is not available and a `lobby.world` is configured, players will be teleported locally to that world name.

Commands
--------
- /cycle setcycle <n> — set the persistent cycle number.
- /cycle cycle-now — request immediate world cycle. On lobby servers this forwards to the hardcore backend (requires `thecycle.cycle` permission). On hardcore servers it triggers generation locally.
- /cycle status — print the current cycle and player counts.

Permissions
- thecycle.cycle — permission needed to run `/cycle cycle-now`.

Proxy (Bungee/Velocity) notes
-----------------------------
- For plugin message based forwarding the plugin uses the BungeeCord plugin messaging channel. Ensure that `ip_forward` is enabled in your proxy and that `server` names in your proxy match `server.hardcore` and `server.lobby` configured in this plugin.
- The plugin will register an outgoing plugin channel and forward RPC requests using the `Forward` submessage where necessary. Some environments may delay channel registration; the plugin queues outbound messages and retries.

HTTP RPC notes
--------------
- The plugin supports a tiny embedded HTTP endpoint for RPCs (`/rpc`). The lobby can POST to the hardcore's `/rpc` endpoint (and vice-versa) and use an HMAC header `X-Signature` computed with `rpc_secret`.
- Use HTTP forwarding when you don't want to rely on a player to send plugin messages or when servers are on separate hosts.
- If the lobby and hardcore run on the same host and use the embedded HTTP listener, the plugin avoids sending notifications to itself.

Server-resource suggestions
---------------------------
These are guideline estimates for small groups (6-8 players):
- Lobby server: 512 MB — 1 GB RAM is typically sufficient if the lobby world is small and you only host a few players.
- Hardcore server (world generation, persistent chunks): 2 - 3 GB RAM is recommended for world generation, plugins, and keeping chunks loaded while generating.
- If you enable large plugins or expect heavier chunk generation, increase RAM accordingly.

Ports
-----
- Default Minecraft server port: 25565.
- In a proxy setup, give each backend a unique port on the host (for local testing you might use 25566 for lobby, 25567 for hardcore). Keep the proxy listening on 25565 and configure `servers` accordingly.

Installing the JAR
------------------
- Place `the-cycle-1.0.0.jar` into `plugins/` on both the hardcore and lobby servers, then restart each server.

Configuration examples
----------------------
- Minimal lobby (forwards to hardcore via HTTP):

server:
  role: "lobby"
  hardcore: "hardcore"
  rpc_secret: "bighardcoreworld"
  http_enabled: true
  http_port: 8080
  http_bind: "0.0.0.0"
  hardcore_http_url: "http://10.0.0.2:8080/rpc"

lobby:
  server: ""
  world: "Lobby"

- Minimal hardcore:

server:
  role: "hardcore"
  rpc_secret: "bighardcoreworld"
  lobby_http_url: "http://10.0.0.3:8080/rpc"
  http_enabled: true
  http_port: 8080
  http_bind: "0.0.0.0"

behavior:
  countdown_send_to_lobby_seconds: 8
  delay_before_generation_seconds: 3
  wait_for_players_to_leave_seconds: 30

Troubleshooting
---------------
- Players not moving to lobby:
  - Check that `lobby.server` matches the proxy server name and that the proxy has `ip_forward` enabled.
  - Check server logs for warnings about plugin channels not being registered. The plugin will fall back to HTTP RPC when configured.
- Lobby forwards but hardcore doesn't react:
  - Verify that the hardcore has either the HTTP RPC endpoint enabled and reachable, or that plugin messaging forwarding reaches the hardcore via the proxy (Bungee).
- New world fails to generate / times out:
  - Large world generation may stall due to heavy chunk processing; ensure the server has enough CPU and memory.
  - The plugin waits for players to leave before generation up to `behavior.wait_for_players_to_leave_seconds`; if players remain the plugin will proceed after the timeout.

Developer notes
---------------
- Unit tests are included in `src/test/java`. Tests avoid constructing a full JavaPlugin and instead use small utilities to exercise file-format logic.

If you want any of the following, say so and I'll implement them next:
- Add a small pre-generation bossbar countdown on the hardcore server (so players see a short countdown before generation starts).
- Add a confirm/abort flow to the cycle command (double-confirm for production servers).
- Integrate with a persistent database for pending moves instead of file-based storage.

License & support
-----------------
This plugin is provided as-is. For changes, bugfixes, or configuration help paste your server logs and config and I'll assist.

