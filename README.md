# The Cycle

A Paper 1.21 plugin to automatically cycle worlds when players die in a hardcore world.

Features
- Create new world per cycle (hardcore_cycle_N)
- Optionally delete previous world folders (sync/async or defer until restart)
- Webhook integration for death recaps
- Optional shared-death mode (one death kills all and triggers a cycle)

Config (src/main/resources/config.yml)
- features.scoreboard: true/false
- features.actionbar: true/false
- features.bossbar: true/false
- webhook.url: Webhook URL for death recaps (optional)
- behavior.cycle_when_no_online_players: true/false
- behavior.delete_previous_worlds: true/false
- behavior.defer_delete_until_restart: true/false
- behavior.async_delete: true/false
- behavior.shared_death: true/false

Commands
- /cycle setcycle <n> — set current cycle number
- /cycle cycle-now — force a cycle
- /cycle status — show current cycle and online players count

Building
Requirements: Java 21, Maven

From project root:

```powershell
mvn -DskipTests package
```

Copy the produced JAR from `target/` to your Paper server `plugins/` folder and start the server.

Testing tips
- For safe testing, set `behavior.delete_previous_worlds: false` to avoid destructive deletions.
- To test deletion behavior, enable `delete_previous_worlds` and try both `async_delete: true` and `false`.
- To test deferred deletion: set `defer_delete_until_restart: true`, run a cycle, check `plugins/HardcoreCycle/pending_deletes.txt` file, restart server to process pending deletions.

Safety
- Deleting world folders is destructive. Keep backups.
- Windows may hold file locks; consider using `defer_delete_until_restart` during testing.

