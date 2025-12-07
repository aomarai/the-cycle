# The Cycle (Hardcore Cycle) Plugin

A Paper 1.21 plugin that automatically "cycles" worlds for a hardcore-style game mode. Each cycle creates a new world (named `hardcore_cycle_N`) and — depending on configuration — unloads and deletes the previous world.

This README documents install, usage, configuration keys (with examples), commands, and testing/troubleshooting steps.

---

## Features

- Create a new world per cycle (names: `hardcore_cycle_1`, `hardcore_cycle_2`, ...)
- Optional deletion of previous world folders (synchronous, asynchronous, or deferred until server restart)
- Webhook support (send a death recap JSON payload when a cycle occurs)
- Optional "shared-death" mode: one death kills all players and triggers a cycle
- Optional lobby support: teleport players to a local world or send them to another server via Bungee/Velocity plugin messaging when a world is unavailable
- Lightweight scoreboard and boss bar display of the current cycle number

---

## Installation

Requirements
- Java 21
- Maven to build the plugin (or use the provided JAR if you already built it)
- Paper 1.21.x server to run the plugin

Build
From the project root run (PowerShell):

```powershell
mvn -DskipTests package
```

Copy the produced JAR from `target/` into your Paper server `plugins/` folder and (re)start the server.

---

## plugin.yml

`src/main/resources/plugin.yml` registers the `/cycle` command. The plugin ships with that file; you do not need to change it unless you want to modify permissions.

Example excerpt (already present in the repository):
```yaml
name: HardcoreCycle
main: dev.wibbleh.the_cycle.Main
version: 1.0.0
api-version: 1.21
commands:
  cycle:
    description: Manage and trigger hardcore world cycles
    usage: /cycle setcycle <n> | /cycle cycle-now | /cycle status
    permission: thecycle.cycle
    permission-message: You do not have permission to use that command.
```

---

## Configuration

Configuration file: `plugins/HardcoreCycle/config.yml` (also `src/main/resources/config.yml` in the project for defaults).

Full default example (explanations follow):

```yaml
features:
  scoreboard: true        # Show cycle on a sidebar scoreboard
  actionbar: true        # Send a short actionbar message on player death
  bossbar: true          # Use a global bossbar to push messages

webhook:
  url: ""               # Optional webhook URL that receives death recap JSON on cycle

behavior:
  cycle_when_no_online_players: true    # Allow cycles even when no players are online
  delete_previous_worlds: true          # Whether previous worlds should be deleted
  defer_delete_until_restart: false     # If true, record deletion for next server start instead of deleting now
  async_delete: true                    # If true perform deletions asynchronously (recommended)
  shared_death: false                   # If true, one death kills all players and triggers a cycle

lobby:
  server: ""            # Optional: Bungee/Velocity server name to send players to (Connect plugin message)
  world: ""             # Optional: Local world name to teleport players to if lobby.server not used
```

Key meanings and recommended values
- `features.scoreboard` — When true a scoreboard is shown with the current cycle number. Defaults to true.
- `features.actionbar` — When true, a brief actionbar is sent to players when someone dies.
- `features.bossbar` — When true the plugin will use a bossbar to display cycle-related messages.
- `webhook.url` — If set to a valid HTTP(S) URL the plugin will POST a simple JSON payload describing the deaths when a cycle completes. Useful for Discord or external logging.
- `behavior.cycle_when_no_online_players` — When false the plugin will not start a cycle if there are no online players (useful for headless world generation policies).
- `behavior.delete_previous_worlds` — When true the plugin attempts to remove the previous world folder after cycling. This is destructive; keep backups.
- `behavior.defer_delete_until_restart` — When true, instead of attempting deletion immediately the plugin records the world name in `pending_deletes.txt` and tries to delete on the next server start. Useful on Windows or when other plugins might lock files.
- `behavior.async_delete` — When true deletions (when not deferred) are performed asynchronously so the main thread isn't blocked. Recommended to enable.
- `behavior.shared_death` — When true, a single player death will cause all players to be killed (server-side) and trigger a cycle. Use with caution.
- `lobby.server` — Optional proxy server name to send players to using the BungeeCord plugin message channel. If used, the plugin will attempt to register the `BungeeCord` outgoing channel at startup — if registration fails the plugin logs a warning and falls back to local lobby.
- `lobby.world` — Optional local world name; if provided and found on this server players will be teleported to its spawn when a world is unavailable.

Examples
- Pure local-lobby configuration:
  - Set `lobby.world: lobby_world` and create a world named `lobby_world` on the same server. If new world generation fails or spawn is unavailable, players will be teleported to `lobby_world`.
- Proxy-lobby (Bungee) configuration:
  - Set `lobby.server: hub1` and place the plugin jar on the backend server; ensure the proxy recognizes `hub1`. The plugin will send a `BungeeCord` "Connect" plugin message to move the player to that named server.

---

## Commands

- `/cycle setcycle <n>` — Set the current cycle number to `<n>` and update the scoreboard (requires permission `thecycle.cycle`).
- `/cycle cycle-now` — Force an immediate cycle (creates next world, moves players, schedules deletion of the previous world according to config).
- `/cycle status` — Shows current cycle and number of players online.

The command is defined in `plugin.yml` as `/cycle` and the plugin uses permission `thecycle.cycle` by default.

---

## Behaviour notes and options (A / B)

- Option A — Immediate/synchronous deletion: set `delete_previous_worlds: true`, `defer_delete_until_restart: false`, and `async_delete: false`. The plugin will attempt to delete the previous world synchronously (may block until deletion completes).

- Option B — Async or deferred deletion (recommended):
  - Async immediate deletion: set `delete_previous_worlds: true`, `defer_delete_until_restart: false`, and `async_delete: true`. The plugin will delete in a background task.
  - Deferred deletion: set `defer_delete_until_restart: true`. The plugin appends the world name to `pending_deletes.txt` in the plugin data folder; deletions are retried on the next server start.

Safeguard: the plugin teleports any players still inside the previous world to the new world's spawn (if available) before attempting to unload/delete the previous world; if the new spawn is not available the plugin sends players to the configured lobby (proxy or local world) when possible.

---

## Testing / validation

1. Install plugin jar in `plugins/` and start server.
2. Check server logs for plugin enable message and for messages about pending deletes (if any).
3. Test non-destructive behavior first by disabling deletion:
   - Set `behavior.delete_previous_worlds: false` and run `/cycle cycle-now`.
   - Confirm new world `hardcore_cycle_2` appears in server files and players are teleported.
4. Test deletion modes carefully (back up worlds first):
   - Set `async_delete: true` and `delete_previous_worlds: true` to test async deletion.
   - Set `defer_delete_until_restart: true` to test deferred deletion and verify `plugins/HardcoreCycle/pending_deletes.txt` is created.
5. Test lobby behavior:
   - For a local lobby set `lobby.world` and ensure that world exists on the server; create the world or move players manually to test.
   - For proxy lobby set `lobby.server` and ensure the proxy is configured; watch for plugin messages in the logs.
6. Webhook: set `webhook.url` to a test endpoint and inspect the POST content when a cycle occurs.

---

## Safety & troubleshooting

- Deleting world folders is irreversible. Keep backups and use `defer_delete_until_restart: true` on Windows to avoid file-lock issues.
- If `BungeeCord` registration fails, the plugin logs a warning and will fall back to `lobby.world` when available.
- If you see issues unloading worlds (unload returns false), check for other plugins holding references or players still in that world.
- If webhook POSTs fail check your URL and network connectivity.

---

## Development notes

- Code is split into small helper classes (`WorldDeletionService`, `WebhookService`, `DeathListener`, `CommandHandler`) for maintainability.
- Javadoc comments have been added to public methods to document behavior.

---

If you want I can also add example `plugin.yml` permission nodes, sample `config.yml` file ready to drop into `plugins/HardcoreCycle/`, or a simple test harness script to exercise cycles in an automated way.
