package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class Main extends JavaPlugin implements Listener {
    private static final Logger LOG = Logger.getLogger("HardcoreCycle");
    private final AtomicInteger cycleNumber = new AtomicInteger(1);
    // Death recap data collected per-cycle
    private final List<Map<String, Object>> deathRecap = new ArrayList<>();
    // Track alive players by UUID
    private final Map<UUID, Boolean> aliveMap = new HashMap<>();
    private FileConfiguration cfg;
    private boolean enableScoreboard;
    private boolean enableBossBar;
    private BossBar bossBar;
    private Objective objective;
    // Lobby configuration: either a server name for Bungee/Velocity or a world name on this server
    private String lobbyServer;
    private String lobbyWorldName;
    private boolean registeredBungeeChannel = false;
    // Cycle tracking
    private File cycleFile;
    // Server role — when false this instance acts as a lobby and must not create/delete worlds
    private boolean isHardcoreBackend = true;
    // RPC / forwarding configuration
    private String rpcSecret = "";
    private String hardcoreServerName = "";
        /**
     * Namespaced RPC plugin channel used for lobby-to-hardcore server forwarding.
     * <p>
     * This channel name must contain a colon (':') to comply with Bukkit/Spigot plugin messaging requirements.
     * It is used for forwarding RPC messages between the lobby and hardcore servers.
     */
    private static final String RPC_CHANNEL = "thecycle:rpc";
    // Outbound RPC queue used when the Bungee outgoing channel isn't available yet.
    private final Deque<byte[]> outboundRpcQueue = new ArrayDeque<>();
    private static final int MAX_RPC_QUEUE = 100;
    private int rpcQueueTaskId = -1;
 // Webhook
     private String webhookUrl;
     private WorldDeletionService worldDeletionService;
     private WebhookService webhookService;
     private CommandHandler commandHandler;
    // Optional embedded HTTP RPC server (started when configured)
    private HttpRpcServer httpRpcServer;
    // Configured HTTP port for the embedded RPC server (for self-notification detection)
    private int configuredHttpPort = 8080;
    // Countdown durations (seconds) for moving players
    private int countdownSendToLobbySeconds = 10;
    private int countdownSendToHardcoreSeconds = 10;
    // Delay (seconds) to wait before starting world generation (configurable)
    private int delayBeforeGenerationSeconds = 3;
    // Maximum seconds to wait for players to leave the previous hardcore world before forcing generation
    private int waitForPlayersToLeaveSeconds = 30;
    // Whether to show a short server-wide pre-generation countdown on the hardcore server
    private boolean preGenerationCountdownEnabled = true;
    // When false, countdown messages are only sent to the command requester (if available); default true
    private boolean countdownBroadcastToAll = true;
    // Optional UUID of the player who requested the last cycle; used to scope countdown messages when configured
    private volatile UUID lastCycleRequester = null;
    // Pending moves for players who are dead at move time; they will be moved on respawn
    private final Set<UUID> pendingLobbyMoves = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> pendingHardcoreMoves = Collections.synchronizedSet(new HashSet<>());
    // File used to persist pending moves across restarts
    private File pendingMovesFile;

    /**
     * Plugin enable lifecycle method. Loads configuration, wires helper services,
     * registers listeners and initializes in-memory state.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();
        // ensure data folder and pending moves file
        pendingMovesFile = new File(getDataFolder(), "pending_moves.json");

        // Read server role (default: hardcore). If role is "lobby" the plugin will not create or delete worlds.
        String role = cfg.getString("server.role", "hardcore").trim().toLowerCase(Locale.ROOT);
        isHardcoreBackend = role.equals("hardcore");

        enableScoreboard = cfg.getBoolean("features.scoreboard", true);
        boolean enableActionbarLocal = cfg.getBoolean("features.actionbar", true);
        enableBossBar = cfg.getBoolean("features.bossbar", true);
        webhookUrl = cfg.getString("webhook.url", "");
        // lobby config
        lobbyServer = cfg.getString("lobby.server", "").trim();
        lobbyWorldName = cfg.getString("lobby.world", "").trim();
        // Register Bungee outgoing channel early so forwards/connects can be sent at runtime.
        try {
            if (!getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord")) {
                getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            }
            registeredBungeeChannel = getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord");
        } catch (Exception e) {
            LOG.warning("Could not register BungeeCord outgoing channel; cross-server lobby will not work. Reason: " + e.getMessage());
            registeredBungeeChannel = false;
        }

        cycleFile = new File(getDataFolder(), "cycles.json");
        loadCycleNumber();
        // wire services
        boolean deletePrev = cfg.getBoolean("behavior.delete_previous_worlds", true);
        boolean deferDelete = cfg.getBoolean("behavior.defer_delete_until_restart", false);
        boolean asyncDelete = cfg.getBoolean("behavior.async_delete", true);
        boolean sharedDeath = cfg.getBoolean("behavior.shared_death", false);

        worldDeletionService = new WorldDeletionService(this, deletePrev, deferDelete, asyncDelete);
        webhookService = new WebhookService(this, webhookUrl);
        DeathListener dl = new DeathListener(this, enableActionbarLocal, sharedDeath, aliveMap, deathRecap);
        commandHandler = new CommandHandler(this);

        // Register command executor and tab completer so /cycle works and supports autocomplete
        var cycleCmd = getCommand("cycle");
        if (cycleCmd != null) {
            cycleCmd.setExecutor(commandHandler);
            cycleCmd.setTabCompleter(commandHandler);
        } else {
            LOG.warning("Command 'cycle' not defined in plugin.yml; tab-complete and execution will not be available.");
        }

        // Register RPC handler: incoming channel on hardcore, and provide outgoing registration for lobby
        this.rpcSecret = cfg.getString("server.rpc_secret", "");
        this.hardcoreServerName = cfg.getString("server.hardcore", "");
        // Optional HTTP RPC URL for lobby to call (if present, prefer HTTP forwarding when available)
        int httpPort = cfg.getInt("server.http_port", 8080);
        configuredHttpPort = httpPort;
        // Read countdown and timing configuration (seconds)
        countdownSendToLobbySeconds = cfg.getInt("behavior.countdown_send_to_lobby_seconds", 10);
        countdownSendToHardcoreSeconds = cfg.getInt("behavior.countdown_send_to_hardcore_seconds", 10);
        countdownBroadcastToAll = cfg.getBoolean("behavior.countdown_broadcast_to_all", true);
        // Delay and wait settings for safe generation
        delayBeforeGenerationSeconds = cfg.getInt("behavior.delay_before_generation_seconds", 3);
        waitForPlayersToLeaveSeconds = cfg.getInt("behavior.wait_for_players_to_leave_seconds", 30);
        preGenerationCountdownEnabled = cfg.getBoolean("behavior.pre_generation_countdown_enabled", true);
        String httpBind = cfg.getString("server.http_bind", "");
        RpcHandler rpcHandler = new RpcHandler(this, this, this.rpcSecret, RPC_CHANNEL);
        try {
            // Incoming channel
            getServer().getMessenger().registerIncomingPluginChannel(this, RPC_CHANNEL, rpcHandler);
            // Outgoing channel (used by lobby instances to forward RPCs)
            getServer().getMessenger().registerOutgoingPluginChannel(this, RPC_CHANNEL);
            LOG.info("Registered RPC plugin channel: " + RPC_CHANNEL + " (role=" + (isHardcoreBackend ? "hardcore" : "lobby") + ")");
        } catch (Exception ex) {
            LOG.warning("Failed to register " + RPC_CHANNEL + " plugin channels: " + ex.getMessage());
        }

        // If HTTP endpoint is enabled in config, start embedded HTTP RPC server on this instance (allows lobby or hardcore to accept RPCs)
        try {
            if (cfg.getBoolean("server.http_enabled", false)) {
                httpRpcServer = new HttpRpcServer(this, httpPort, httpBind);
                httpRpcServer.start();
                LOG.info("Started embedded HTTP RPC server on port " + httpPort + (httpBind.isEmpty() ? "" : " bound to " + httpBind));
            }
        } catch (Exception e) {
            LOG.warning("Failed to start embedded HTTP RPC server: " + e.getMessage());
            httpRpcServer = null;
        }

        worldDeletionService.processPendingDeletions();

        // Load persisted pending moves (if any)
        loadPendingMoves();

        // Schedule a periodic task to try to drain the outbound RPC queue (runs on main thread)
        if (rpcQueueTaskId == -1) {
            rpcQueueTaskId = Bukkit.getScheduler().runTaskTimer(this, this::drainRpcQueue, 20L, 20L).getTaskId();
        }

        getServer().getPluginManager().registerEvents(dl, this);

        if (enableBossBar) {
            bossBar = Bukkit.createBossBar("Next world in progress...", BarColor.GREEN, BarStyle.SOLID);
        }

        if (enableScoreboard) {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            // Use Component-based display name for newer server APIs (avoids deprecated overload)
            objective = scoreboard.registerNewObjective("hc_cycle", "dummy", Component.text("Hardcore Cycle"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            updateScoreboard();
        }

        Bukkit.getOnlinePlayers().forEach(p -> aliveMap.put(p.getUniqueId(), true));

        LOG.info("TheCyclePlugin enabled — cycle #" + cycleNumber.get() + "; role=" + (isHardcoreBackend ? "hardcore" : "lobby") + ", bungeeRegistered=" + registeredBungeeChannel);
    }

    /**
     * Plugin disable lifecycle method. Persist cycle number to disk.
     */
    @Override
    public void onDisable() {
        writeCycleFile(cycleNumber.get());
        savePendingMoves();
    }

    /**
     * Return the current cycle number.
     *
     * @return current cycle counter (1-based)
     */
    public int getCycleNumber() {
        return cycleNumber.get();
    }

    /**
     * Set the cycle number, persist to disk and update the scoreboard.
     *
     * @param n new cycle number
     */
    public void setCycleNumber(int n) {
        cycleNumber.set(n);
        writeCycleFile(n);
        updateScoreboard();
    }

    /**
     * Public wrapper to trigger a world cycle from other components.
     * Delegates to performCycle which does the heavy lifting.
     */
    public void triggerCycle() {
        performCycle();
    }

    /**
     * Perform the world cycle: increment cycle number, persist, optionally send webhook,
     * create a new world, move players into the new world (or lobby), and schedule deletion
     * of the previous world according to configuration.
     *
     * This method is synchronized to avoid concurrent cycles.
     */
    public synchronized void performCycle() {
        if (!isHardcoreBackend) {
            // Prevent accidental world creation on lobby instances.
                        LOG.warning("World cycle attempted on a server configured as 'lobby'. This server will not create worlds. Please run /cycle on your hardcore backend.");
            return;
        }
        int next = cycleNumber.incrementAndGet();
        updateScoreboard();
        writeCycleFile(next);

        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            webhookService.send(buildWebhookPayload(next, deathRecap));
        }

        deathRecap.clear();

        // Schedule generation after ensuring previous-world players have left (or timeout).
        final int cycleNum = next;
        final String prevWorldName = "hardcore_cycle_" + (next - 1);
        // Move players out of previous world if present
        if (next > 1 && cfg.getBoolean("behavior.delete_previous_worlds", true)) {
            World prevWorld = Bukkit.getWorld(prevWorldName);
            if (prevWorld != null) {
                if (preGenerationCountdownEnabled) {
                    LOG.info("Scheduling countdown to move players out of previous world '" + prevWorldName + "' to lobby before generating new world.");
                    // Schedule countdown to move players so clients have time to transition. This will mark dead players as pending.
                    scheduleCountdownThenSendPlayersToLobby(prevWorld.getPlayers(), countdownSendToLobbySeconds);
                } else {
                    LOG.info("Moving players out of previous world '" + prevWorldName + "' to lobby immediately (pre-generation countdown disabled).");
                    for (Player p : prevWorld.getPlayers()) {
                        try { sendPlayerToLobby(p); } catch (Exception ex) { LOG.warning("Failed to move player " + p.getName() + " to lobby before generation: " + ex.getMessage()); }
                        aliveMap.put(p.getUniqueId(), true);
                    }
                }
            } else {
                LOG.fine("Previous world '" + prevWorldName + "' not loaded; nothing to move.");
            }
        }

        // Start a short delay before attempting generation, then poll for remaining players leaving the previous world
        Bukkit.getScheduler().runTaskLater(this, () -> {
            // If we should wait for players to leave, poll once per second up to the configured timeout
            if (waitForPlayersToLeaveSeconds > 0 && next > 1 && cfg.getBoolean("behavior.delete_previous_worlds", true)) {
                final int[] elapsed = {0};
                final org.bukkit.scheduler.BukkitTask[] taskHolder = new org.bukkit.scheduler.BukkitTask[1];
                taskHolder[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
                    World pw = Bukkit.getWorld(prevWorldName);
                    boolean empty = (pw == null) || pw.getPlayers().isEmpty();
                    if (empty || elapsed[0] >= waitForPlayersToLeaveSeconds) {
                        if (!empty) {
                            LOG.info("Timeout waiting for previous-world players to leave; forcing move to lobby and proceeding with generation after grace period.");
                            // Forcibly move remaining players to lobby now
                            World pwForce = Bukkit.getWorld(prevWorldName);
                            if (pwForce != null) {
                                for (Player r : pwForce.getPlayers()) {
                                    try { sendPlayerToLobby(r); } catch (Exception ex) { LOG.warning("Failed to force-move player " + r.getName() + " to lobby: " + ex.getMessage()); }
                                }
                            }
                            // cancel polling
                            if (taskHolder[0] != null) taskHolder[0].cancel();
                            // wait a short grace period (3s) to allow client transfers to initiate, then proceed with unload/generation
                            Bukkit.getScheduler().runTaskLater(this, () -> {
                                World pw2 = Bukkit.getWorld(prevWorldName);
                                if (pw2 != null) {
                                    boolean unloaded = Bukkit.unloadWorld(pw2, false);
                                    if (!unloaded) LOG.warning("Failed to unload previous world '" + prevWorldName + "' prior to generation; scheduling deletion fallback.");
                                    worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
                                }
                                doGenerateWorld(cycleNum);
                            }, 60L); // 3 seconds grace
                            return;
                        }
                        // empty: cancel and proceed immediately
                        if (taskHolder[0] != null) taskHolder[0].cancel();
                        World pw2 = Bukkit.getWorld(prevWorldName);
                        if (pw2 != null) {
                            boolean unloaded = Bukkit.unloadWorld(pw2, false);
                            if (!unloaded) LOG.warning("Failed to unload previous world '" + prevWorldName + "' prior to generation; scheduling deletion fallback.");
                            worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
                        }
                        doGenerateWorld(cycleNum);
                        return;
                    }
                    elapsed[0]++;
                }, 0L, 20L);
            } else {
                // No wait requested — either not configured or not relevant — generate after the initial delay
                doGenerateWorld(cycleNum);
            }
        }, Math.max(0, delayBeforeGenerationSeconds) * 20L);
        // performCycle returns; generation continues asynchronously
    }

    /**
     * Generate the world for the given cycle number and perform post-generation steps.
     * Must be invoked on the main server thread.
     *
     * @param next cycle number to generate
     */
    private void doGenerateWorld(int next) {
        String newWorldName = "hardcore_cycle_" + next;
        LOG.info("Generating world: " + newWorldName);
        Bukkit.getOnlinePlayers().forEach(p -> { try { p.sendMessage("[HardcoreCycle] Generating world: " + newWorldName); } catch (Exception ignored) {} });

        World newWorld = null;
        try {
            newWorld = Bukkit.createWorld(new org.bukkit.WorldCreator(newWorldName));
        } catch (Exception e) {
            LOG.warning("Failed to create new world '" + newWorldName + "': " + e.getMessage());
        }

        // Handle previous world deletion/teleporting
        if (next > 1 && cfg.getBoolean("behavior.delete_previous_worlds", true)) {
            String prevWorldName = "hardcore_cycle_" + (next - 1);
            World prevWorld = Bukkit.getWorld(prevWorldName);
            if (prevWorld != null) {
                if (newWorld != null) {
                    try {
                        org.bukkit.Location spawn = newWorld.getSpawnLocation();
                        if (spawn != null) {
                            for (Player p : prevWorld.getPlayers()) {
                                try { p.teleport(spawn); } catch (Exception ex) { LOG.warning("Failed to teleport player " + p.getName() + " out of " + prevWorldName + ": " + ex.getMessage()); }
                                aliveMap.put(p.getUniqueId(), true);
                            }
                        } else {
                            scheduleCountdownThenSendPlayersToLobby(Bukkit.getOnlinePlayers(), countdownSendToLobbySeconds);
                        }
                    } catch (Exception ex) {
                        LOG.warning("Error while teleporting players from previous world: " + ex.getMessage());
                    }
                } else {
                    LOG.warning("New world is null; teleporting players from " + prevWorldName + " to lobby instead.");
                    scheduleCountdownThenSendPlayersToLobby(Bukkit.getOnlinePlayers(), countdownSendToLobbySeconds);
                }

                LOG.info("Unloading previous world: " + prevWorldName);
                boolean unloaded = Bukkit.unloadWorld(prevWorld, false);
                if (!unloaded) {
                    LOG.warning("Failed to unload world " + prevWorldName + "; scheduling deletion fallback.");
                    worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
                } else {
                    worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
                }
            } else {
                worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
            }
        }

        // Post-generation player movement
        if (newWorld != null) {
            final org.bukkit.Location spawn = newWorld.getSpawnLocation();
            if (spawn != null) {
                Bukkit.getOnlinePlayers().forEach(p -> {
                    try { p.teleport(spawn); } catch (Exception ex) { LOG.warning("Failed to teleport player " + p.getName() + " to new world: " + ex.getMessage()); }
                    aliveMap.put(p.getUniqueId(), true);
                });
            } else {
                LOG.warning("New world spawn is null; sending players to configured lobby (if any).");
                scheduleCountdownThenSendPlayersToLobby(Bukkit.getOnlinePlayers(), countdownSendToLobbySeconds);
            }

            try { notifyLobbyWorldReady(next); } catch (Exception e) { LOG.warning("Failed to notify lobby that world is ready: " + e.getMessage()); }
        } else {
            LOG.warning("New world is null; sending players to configured lobby (if any).");
            Bukkit.getOnlinePlayers().forEach(this::sendPlayerToLobby);
        }

        pushBossbar("World cycled — welcome to cycle #" + next);

        LOG.info("Cycle " + next + " complete.");
    }

    /**
     * Ensure the plugin data folder exists. If creation fails a warning is logged.
     */
    private void ensureDataFolderExists() {
        File df = getDataFolder();
        if (!df.exists()) {
            try {
                if (!df.mkdirs()) LOG.warning("Failed to create data folder: " + df.getAbsolutePath());
            } catch (Exception e) {
                LOG.warning("Failed to create data folder: " + e.getMessage());
            }
        }
    }

    /**
     * Load the cycle number from disk. If the file is missing or invalid this defaults to 1.
     */
    private void loadCycleNumber() {
        try {
            ensureDataFolderExists();
            if (!cycleFile.exists()) {
                writeCycleFile(cycleNumber.get());
                return;
            }
            try (BufferedReader r = new BufferedReader(new FileReader(cycleFile))) {
                String s = r.readLine();
                if (s != null && !s.trim().isEmpty()) {
                    int n = Integer.parseInt(s.trim());
                    cycleNumber.set(n);
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to read cycle file, defaulting to 1: " + e.getMessage());
        }
    }

    /**
     * Persist the given cycle number to the plugin data folder (cycles.json).
     *
     * @param n cycle number to write
     */
    private void writeCycleFile(int n) {
        try {
            ensureDataFolderExists();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(cycleFile))) {
                w.write(String.valueOf(n));
            }
        } catch (Exception e) {
            LOG.severe("Unable to write cycle file: " + e.getMessage());
        }
    }

    /**
     * Update the server scoreboard with the current cycle number when enabled.
     */
    private void updateScoreboard() {
        if (!enableScoreboard || objective == null) return;
        objective.getScore("Cycle:").setScore(cycleNumber.get());
    }

    /**
     * Update the boss bar title for all players if boss bar support is enabled.
     *
     * @param message message to display to players
     */
    private void pushBossbar(String message) {
        if (!enableBossBar || bossBar == null) return;
        bossBar.setTitle(message);
        // Add all online players to show
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (!bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
        });
    }

    /**
     * Build a JSON payload for the webhook based on the death recap.
     *
     * @param cycleNum cycle number
     * @param recap    death recap list to include
     * @return JSON string payload
     */
    private String buildWebhookPayload(int cycleNum, List<Map<String, Object>> recap) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"content\":null,\"embeds\":[{\"title\":\"Hardcore cycle ")
                .append(cycleNum)
                .append(" complete\",\"description\":\"Server generated new world for cycle #")
                .append(cycleNum)
                .append("\",\"fields\":[");

        for (int i = 0; i < recap.size(); i++) {
            Map<String, Object> e = recap.get(i);
            String name = (String) e.get("name");
            String time = (String) e.get("time");
            String cause = (String) e.get("cause");
            String loc = (String) e.get("location");
            @SuppressWarnings("unchecked")
            List<String> drops = (List<String>) e.get("drops");

            sb.append("{\"name\":\"").append(escape(name + " @ " + loc))
                    .append("\",\"value\":\"").append(escape("Time: " + time + "\\nCause: " + cause + "\\nDrops: " + String.join(", ", drops)))
                    .append("\",\"inline\":false}");
            if (i < recap.size() - 1) sb.append(",");
        }

        sb.append("]}]}");
        return sb.toString();
    }

    /**
     * Escape a string for inclusion in a JSON string value. This is a minimal escaper
     * suitable for simple text fields and does not replace all possible Unicode escapes.
     *
     * @param s input string
     * @return escaped string safe for use inside a JSON string value
     */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    /**
     * Send a player to the configured lobby. Priority:
     * 1) If lobbyServer is configured and Bungee is registered, send a Bungee Connect message.
     * 2) Else if lobbyWorldName exists on this server, teleport the player there.
     * 3) Otherwise log a warning and leave the player in place.
     *
     * @param p player to move to lobby
     */
    private boolean sendPlayerToLobby(Player p) {
        if (p == null) return false;
        if (p.isDead()) {
            // Player is on death screen; mark pending move and log. Actual move will occur on respawn.
            pendingLobbyMoves.add(p.getUniqueId());
            savePendingMovesAsync();
            LOG.info("Player " + p.getName() + " is dead; will send to lobby on respawn.");
            return true;
        }
        if (!lobbyServer.isEmpty() && registeredBungeeChannel) {
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                 DataOutputStream out = new DataOutputStream(outputStream)) {
                out.writeUTF("Connect");
                out.writeUTF(lobbyServer);
                p.sendPluginMessage(this, "BungeeCord", outputStream.toByteArray());
                LOG.info("Sent player " + p.getName() + " to lobby server: " + lobbyServer);
                return true;
            } catch (Exception e) {
                LOG.warning("Failed to send player to Bungee lobby: " + e.getMessage());
            }
        }

        if (!lobbyWorldName.isEmpty()) {
            World lw = Bukkit.getWorld(lobbyWorldName);
            if (lw != null) {
                try {
                    p.teleport(lw.getSpawnLocation());
                    aliveMap.put(p.getUniqueId(), true);
                    LOG.info("Teleported player " + p.getName() + " to lobby world: " + lobbyWorldName);
                    return true;
                } catch (Exception e) {
                    LOG.warning("Failed to teleport player to lobby world: " + e.getMessage());
                }
            } else {
                LOG.warning("Configured lobby world '" + lobbyWorldName + "' not found on this server.");
            }
        }

        LOG.warning("No lobby configured; cannot move player " + p.getName() + ".");
        return false;
    }

    /**
     * Handle plugin commands by delegating to the CommandHandler.
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        return commandHandler.handle(sender, cmd, label, args);
    }

    /**
     * Return whether this instance is configured as the hardcore backend.
     *
     * @return true when this server should create/delete worlds.
     */
    public boolean isHardcoreBackend() {
        return isHardcoreBackend;
    }

    /**
     * Send an RPC action to the configured hardcore backend using the BungeeCord Forward
     * mechanism. Returns true when a send attempt was made (not a guarantee of execution).
     *
     * @param action action string (e.g. "cycle-now")
     * @param requester the command sender requesting the action (may be null)
     * @return true if message was sent; false otherwise
     */
    public boolean sendRpcToHardcore(String action, org.bukkit.command.CommandSender requester) {
         if (action == null || action.isEmpty()) return false;
         // remember who requested the cycle so countdown messages can be scoped to them if configured
         if (requester instanceof org.bukkit.entity.Player) setLastCycleRequester(((org.bukkit.entity.Player) requester).getUniqueId());
         if (hardcoreServerName == null || hardcoreServerName.isEmpty()) {
             LOG.warning("Hardcore server name not configured; cannot forward RPC.");
             // clear requester marker when forwarding fails
             clearLastCycleRequester();
             return false;
         }

         // If configured, prefer HTTP forwarding (does not need a player). The config key server.hardcore_http_url
         // should be a full URL like http://hardcore-host:8080/rpc
         String hardcoreHttpUrl = cfg.getString("server.hardcore_http_url", "").trim();
         if (!hardcoreHttpUrl.isEmpty()) {
             try {
                 String caller = requester instanceof org.bukkit.entity.Player ? ((org.bukkit.entity.Player) requester).getUniqueId().toString() : "console";
                 String payload = "{\"action\":\"" + action + "\",\"caller\":\"" + caller + "\"}";
                 String sig = RpcHttpUtil.computeHmacHex(rpcSecret, payload);
                 java.net.URL u = new java.net.URL(hardcoreHttpUrl);
                 java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                 conn.setRequestMethod("POST");
                 conn.setDoOutput(true);
                 conn.setRequestProperty("Content-Type", "application/json");
                 conn.setRequestProperty("X-Signature", sig);
                 conn.setConnectTimeout(3000);
                 conn.setReadTimeout(5000);
                 try (OutputStream os = conn.getOutputStream()) {
                     os.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                 }
                 int code = conn.getResponseCode();
                 if (code >= 200 && code < 300) {
                     LOG.info("Forwarded RPC via HTTP to " + hardcoreHttpUrl + " status=" + code);
                     return true;
                 } else {
                     LOG.warning("HTTP RPC forward returned non-2xx: " + code);
                 }
             } catch (Exception e) {
                 LOG.warning("HTTP RPC forward failed: " + e.getMessage());
             }
         }

         // Determine player to send plugin message through
         org.bukkit.entity.Player through = null;
         if (requester instanceof org.bukkit.entity.Player) through = (org.bukkit.entity.Player) requester;
         else {
             for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) { through = p; break; }
         }
         if (through == null) {
             LOG.warning("No online player to send plugin message; cannot forward RPC to hardcore.");
             return false;
         }

         // Ensure the Bungee outgoing plugin channel is registered. Some runtime environments
         // (unit tests) may not have a non-null server; in that case we skip registration so
         // tests that mock Player.sendPluginMessage can still execute. At runtime we attempt
         // a registration and abort if it fails.
         if (!registeredBungeeChannel) {
            try {
                if (getServer() != null) {
                    // Prefer the safer isOutgoingChannelRegistered check before registering
                    if (!getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord")) {
                        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
                    }
                    // verify registration
                    if (getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord")) {
                        registeredBungeeChannel = true;
                    } else {
                        LOG.warning("Bungee outgoing channel registration did not take effect; cannot forward RPC.");
                        return false;
                    }
                } else {
                    // No server available (likely in unit test environment) — proceed without registration.
                }
            } catch (Exception e) {
                LOG.warning("Failed to register Bungee outgoing channel; cannot forward RPC: " + e.getMessage());
                return false;
            }
        }

         try (java.io.ByteArrayOutputStream payloadStream = new java.io.ByteArrayOutputStream();
              java.io.DataOutputStream payloadOut = new java.io.DataOutputStream(payloadStream)) {
             String caller = requester instanceof org.bukkit.entity.Player ? ((org.bukkit.entity.Player) requester).getUniqueId().toString() : "console";
             String payload = "rpc::" + (rpcSecret == null ? "" : rpcSecret) + "::" + action + "::" + caller;
             payloadOut.writeUTF(payload);
             payloadOut.flush();
             byte[] payloadBytes = payloadStream.toByteArray();

             // Build Bungee Forward packet: subchannel Forward, target server, channel, short length, data
             try (java.io.ByteArrayOutputStream outStream = new java.io.ByteArrayOutputStream();
                  java.io.DataOutputStream out = new java.io.DataOutputStream(outStream)) {
                 out.writeUTF("Forward");
                 out.writeUTF(hardcoreServerName);
                 out.writeUTF(RPC_CHANNEL);
                 out.writeShort(payloadBytes.length);
                 out.write(payloadBytes);
                 out.flush();
                 // Attempt immediate send; if it fails due to unregistered channel, enqueue the packet for retry
                 try {
                    through.sendPluginMessage(this, "BungeeCord", outStream.toByteArray());
                } catch (IllegalArgumentException iae) {
                    LOG.warning("Send failed due to unregistered channel; enqueueing RPC for retry: " + iae.getMessage());
                    enqueueOutboundRpc(outStream.toByteArray());
                    return true; // treat as accepted (queued)
                } catch (Exception ex) {
                    LOG.warning("Send failed; enqueueing RPC for retry: " + ex.getMessage());
                    enqueueOutboundRpc(outStream.toByteArray());
                    return true;
                }
             }

             LOG.info("Forwarded RPC action '" + action + "' to hardcore server: " + hardcoreServerName);
             return true;
         } catch (Exception e) {
             LOG.warning("Failed to forward RPC to hardcore: " + e.getMessage());
             return false;
         }
     }

    /**
     * Send a player to a specific server via the BungeeCord plugin channel (Connect subcommand).
     * Returns true when a send attempt was made.
     */
    public boolean sendPlayerToServer(org.bukkit.entity.Player p, String serverName) {
        if (p == null || serverName == null || serverName.isEmpty()) return false;
        if (p.isDead()) {
            pendingHardcoreMoves.add(p.getUniqueId());
            savePendingMovesAsync();
            LOG.info("Player " + p.getName() + " is dead; will move to hardcore on respawn.");
            return true;
        }
        if (!registeredBungeeChannel) {
            try {
                if (getServer() != null) {
                    if (!getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord")) {
                        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
                    }
                    registeredBungeeChannel = getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord");
                }
            } catch (Exception e) {
                LOG.warning("Cannot send player to server; Bungee outgoing channel unavailable: " + e.getMessage());
                return false;
            }
        }

        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             java.io.DataOutputStream out = new java.io.DataOutputStream(baos)) {
            out.writeUTF("Connect");
            out.writeUTF(serverName);
            out.flush();
            p.sendPluginMessage(this, "BungeeCord", baos.toByteArray());
            LOG.info("Sent player " + p.getName() + " to server: " + serverName);
            return true;
        } catch (Exception e) {
            LOG.warning("Failed to send player to server " + serverName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Enqueue an outbound RPC packet for later delivery when Bungee channel/player is available.
     */
    private synchronized void enqueueOutboundRpc(byte[] packet) {
        if (packet == null || packet.length == 0) return;
        if (outboundRpcQueue.size() >= MAX_RPC_QUEUE) {
            outboundRpcQueue.pollFirst();
            LOG.warning("Outbound RPC queue full; dropping oldest message.");
        }
        outboundRpcQueue.addLast(packet);
        LOG.info("Enqueued outbound RPC; queue size=" + outboundRpcQueue.size());
    }

    /**
     * Attempt to drain queued outbound RPC packets. Runs on the main thread via scheduler.
     */
    private synchronized void drainRpcQueue() {
        if (outboundRpcQueue.isEmpty()) return;
        // ensure outgoing channel
        try {
            if (!registeredBungeeChannel && getServer() != null) {
                if (!getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord")) {
                    getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
                }
                registeredBungeeChannel = getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord");
            }
        } catch (Exception e) {
            LOG.warning("Periodic RPC drain: failed to ensure Bungee outgoing channel: " + e.getMessage());
            return;
        }

        if (!registeredBungeeChannel) return;

        org.bukkit.entity.Player through = null;
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) { through = p; break; }
        if (through == null) return;

        while (!outboundRpcQueue.isEmpty()) {
            byte[] pkt = outboundRpcQueue.peekFirst();
            if (pkt == null) { outboundRpcQueue.pollFirst(); continue; }
            try {
                through.sendPluginMessage(this, "BungeeCord", pkt);
                outboundRpcQueue.pollFirst();
                LOG.info("Delivered queued RPC; remaining=" + outboundRpcQueue.size());
            } catch (Exception e) {
                LOG.warning("Failed to send queued RPC during drain: " + e.getMessage());
                break; // stop on first failure
            }
        }
    }

    /**
     * Notify configured lobby HTTP endpoint that a world is ready. Fire-and-forget.
     */
    private void notifyLobbyWorldReady(int cycle) {
        if (cfg == null) return;
        String lobbyUrl = cfg.getString("server.lobby_http_url", "").trim();
        if (lobbyUrl.isEmpty()) {
            LOG.info("No lobby_http_url configured; skipping world-ready notification.");
            return;
        }
        try {
            String payload = "{\"action\":\"world-ready\",\"cycle\":" + cycle + "}";
            // Avoid notifying ourself: if lobbyUrl points to our own embedded HTTP listener, skip the POST.
            try {
                java.net.URL parsed = new java.net.URL(lobbyUrl);
                String host = parsed.getHost();
                int port = parsed.getPort() == -1 ? parsed.getDefaultPort() : parsed.getPort();
                boolean isLoopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || host.equals(java.net.InetAddress.getLocalHost().getHostAddress());
                if (isLoopback && httpRpcServer != null && port == configuredHttpPort) {
                    LOG.info("Lobby URL points to this server's own HTTP listener; skipping HTTP notify to avoid self-delivery: " + lobbyUrl);
                    return;
                }
            } catch (Exception e) {
                // ignore URL parse errors and continue attempting to notify
            }

            String sig = RpcHttpUtil.computeHmacHex(rpcSecret, payload);
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(lobbyUrl))
                        .timeout(java.time.Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header("X-Signature", sig)
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8))
                        .build();
                java.net.http.HttpResponse<Void> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
                int code = resp.statusCode();
                if (code >= 200 && code < 300) LOG.info("Notified lobby of world-ready: " + lobbyUrl + " status=" + code);
                else LOG.warning("Lobby notification returned non-2xx: " + code);
            } catch (Exception e) {
                LOG.warning("Failed to notify lobby of world-ready: " + e.getMessage());
            }
        } catch (Exception e) {
            LOG.warning("Failed to notify lobby of world-ready: " + e.getMessage());
        }
    }

     /**
      * Getter for the configured hardcore server name used for forwarding.
      */
     public String getHardcoreServerName() { return hardcoreServerName; }

    /**
     * Schedule a countdown in chat for the provided players, then send them to the lobby when it elapses.
     * The countdown runs on the main server thread and sends a message every second.
     */
    public void scheduleCountdownThenSendPlayersToLobby(Collection<? extends Player> players, int seconds) {
        if (players == null) return;
        if (players.isEmpty() || seconds <= 0) {
            // immediate send for zero/invalid durations
            for (Player p : players) sendPlayerToLobby(p);
            return;
        }
        // Determine effective target players based on broadcast config and last requester
        Collection<? extends Player> targets;
        if (!countdownBroadcastToAll && lastCycleRequester != null) {
            Player req = Bukkit.getPlayer(lastCycleRequester);
            if (req != null && players.contains(req)) targets = List.of(req);
            else targets = players;
        } else {
            targets = players;
        }
        // Add all targets to pending moves so respawn will trigger a move if they're dead when countdown ends
        for (Player p : targets) { if (p != null) pendingLobbyMoves.add(p.getUniqueId()); }
        savePendingMovesAsync();
        final int total = seconds;
        new org.bukkit.scheduler.BukkitRunnable() {
             int remaining = total;
             @Override
             public void run() {
                 if (remaining <= 0) {
                    for (Player p : targets) {
                         if (p == null) continue;
                         if (p.isDead()) {
                             // keep in pending set and wait for respawn
                             LOG.info("Player " + p.getName() + " still dead at countdown end; will move on respawn.");
                         } else {
                             pendingLobbyMoves.remove(p.getUniqueId());
                             savePendingMovesAsync();
                             sendPlayerToLobby(p);
                         }
                     }
                     // cleanup UI/state
                     if (enableBossBar && bossBar != null) {
                         bossBar.removeAll();
                     }
                     clearLastCycleRequester();
                     cancel();
                      return;
                 }
                // Update bossbar and chat messages
                if (enableBossBar && bossBar != null) {
                    bossBar.setTitle("Returning to lobby in " + remaining + "s");
                    Bukkit.getOnlinePlayers().forEach(pl -> { if (!bossBar.getPlayers().contains(pl)) bossBar.addPlayer(pl); });
                }
                for (Player p : targets) {
                    if (p == null) continue;
                    try { p.sendMessage("[HardcoreCycle] Sending you to the lobby in " + remaining + " second(s)..."); } catch (Exception ignored) {}
                }
                remaining--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    /**
     * Schedule a countdown in chat on the lobby and then move players to the configured hardcore server.
     */
    public void scheduleCountdownThenMovePlayersToHardcore(int seconds) {
        String target = getHardcoreServerName();
        if (target == null || target.isEmpty()) {
            LOG.warning("Hardcore server name not configured; cannot move players to hardcore.");
            return;
        }
        if (seconds <= 0) {
            for (Player p : Bukkit.getOnlinePlayers()) sendPlayerToServer(p, target);
            return;
        }
        // Determine targets depending on broadcast config and last requester
        Collection<? extends Player> targets;
        if (!countdownBroadcastToAll && lastCycleRequester != null) {
            Player req = Bukkit.getPlayer(lastCycleRequester);
            if (req != null) targets = List.of(req);
            else targets = Bukkit.getOnlinePlayers();
        } else {
            targets = Bukkit.getOnlinePlayers();
        }
        // If there are no players on the lobby to move, do nothing.
        if (targets == null || targets.isEmpty()) {
            LOG.info("No players on lobby to move to hardcore; skipping scheduled move.");
            clearLastCycleRequester();
            return;
        }
        // Mark targets as pending hardcore moves
        for (Player p : targets) { if (p != null) pendingHardcoreMoves.add(p.getUniqueId()); }
        savePendingMovesAsync();
        final int total = seconds;
        new org.bukkit.scheduler.BukkitRunnable() {
            int remaining = total;
            @Override
            public void run() {
                if (remaining <= 0) {
                    for (Player p : targets) {
                        if (p == null) continue;
                        if (p.isDead()) {
                            LOG.info("Player " + p.getName() + " still dead at hardcore countdown end; will move on respawn.");
                        } else {
                            pendingHardcoreMoves.remove(p.getUniqueId());
                            savePendingMovesAsync();
                            sendPlayerToServer(p, target);
                        }
                    }
                    // cleanup UI/state
                    if (enableBossBar && bossBar != null) {
                        bossBar.removeAll();
                    }
                    clearLastCycleRequester();
                    cancel();
                     return;
                 }
                // Update bossbar and chat messages
                if (enableBossBar && bossBar != null) {
                    bossBar.setTitle("Moving to hardcore in " + remaining + "s");
                    Bukkit.getOnlinePlayers().forEach(pl -> { if (!bossBar.getPlayers().contains(pl)) bossBar.addPlayer(pl); });
                }
                for (Player p : targets) {
                    try { p.sendMessage("[HardcoreCycle] Moving you to hardcore server in " + remaining + " second(s)..."); } catch (Exception ignored) {}
                }
                remaining--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    /**
     * Persist pending move sets to disk synchronously.
     */
    private synchronized void savePendingMoves() {
        try {
            ensureDataFolderExists();
            if (pendingMovesFile == null) pendingMovesFile = new File(getDataFolder(), "pending_moves.json");
            PendingMovesStorage.save(pendingMovesFile, pendingLobbyMoves, pendingHardcoreMoves);
        } catch (Exception e) {
            LOG.warning("Failed to persist pending moves: " + e.getMessage());
        }
    }

    /**
     * Persist pending moves asynchronously to avoid blocking main thread.
     */
    private void savePendingMovesAsync() {
        // run async to file
        Bukkit.getScheduler().runTaskAsynchronously(this, this::savePendingMoves);
    }

    /**
     * Load pending moves from disk if present.
     */
    private synchronized void loadPendingMoves() {
        try {
            ensureDataFolderExists();
            if (pendingMovesFile == null) pendingMovesFile = new File(getDataFolder(), "pending_moves.json");
            if (!pendingMovesFile.exists()) return;
            pendingLobbyMoves.clear(); pendingHardcoreMoves.clear();
            PendingMovesStorage.load(pendingMovesFile, pendingLobbyMoves, pendingHardcoreMoves);
        } catch (Exception e) {
            LOG.warning("Failed to load pending moves: " + e.getMessage());
        }
    }

    /**
     * Clear any pending moves for a given player (used on quit).
     */
    public void clearPendingFor(UUID id) {
        if (id == null) return;
        boolean changed = pendingLobbyMoves.remove(id) | pendingHardcoreMoves.remove(id);
        if (changed) savePendingMovesAsync();
    }

    /**
     * Set the UUID of the player who requested the last cycle.
     */
    public void setLastCycleRequester(UUID id) { this.lastCycleRequester = id; }

    /**
     * Clear the last cycle requester marker.
     */
    public void clearLastCycleRequester() { this.lastCycleRequester = null; }

    /**
     * Mark a player UUID as pending to be moved to the lobby (used when the player is currently dead).
     * This is public so external components (listeners) can schedule pending moves without calling private helpers.
     * @param id player UUID to mark
     */
    public void addPendingLobbyMove(UUID id) {
        if (id == null) return;
        pendingLobbyMoves.add(id);
        savePendingMovesAsync();
    }

    /**
     * Mark a player UUID as pending to be moved to the hardcore server (used when the player is currently dead).
     * @param id player UUID to mark
     */
    public void addPendingHardcoreMove(UUID id) {
        if (id == null) return;
        pendingHardcoreMoves.add(id);
        savePendingMovesAsync();
    }

    /**
     * Called when a player respawns; marks them alive and applies any pending moves.
     * If the player had a pending move to the lobby/hardcore, remove the pending marker,
     * persist pending state and attempt to move the player.
     *
     * @param p respawned player
     */
     public void handlePlayerRespawn(org.bukkit.entity.Player p) {
         if (p == null) return;
         UUID id = p.getUniqueId();
         aliveMap.put(id, true);

         // Schedule actual move actions on next tick to ensure we are fully respawned.
         Bukkit.getScheduler().runTask(this, () -> {
             boolean changed = false;
             if (pendingLobbyMoves.contains(id)) {
                 try {
                     boolean sent = false;
                     try { sent = sendPlayerToLobby(p); } catch (Exception e) { LOG.warning("Failed to send respawned player to lobby: " + e.getMessage()); }
                     if (sent) {
                         pendingLobbyMoves.remove(id);
                         changed = true;
                     } else {
                         LOG.info("Will retry pending lobby move for " + p.getName() + " on next respawn or server restart.");
                     }
                 } catch (Exception ex) { LOG.warning("Error while processing pending lobby move: " + ex.getMessage()); }
             }

             if (pendingHardcoreMoves.contains(id)) {
                 try {
                     boolean sent = false;
                     try { sent = sendPlayerToServer(p, hardcoreServerName); } catch (Exception e) { LOG.warning("Failed to send respawned player to hardcore: " + e.getMessage()); }
                     if (sent) {
                         pendingHardcoreMoves.remove(id);
                         changed = true;
                     } else {
                         LOG.info("Will retry pending hardcore move for " + p.getName() + " on next respawn or server restart.");
                     }
                 } catch (Exception ex) { LOG.warning("Error while processing pending hardcore move: " + ex.getMessage()); }
             }

             if (changed) savePendingMovesAsync();
         });
     }

    /**
     * Return the configured countdown duration for sending players to the lobby.
     */
    public int getCountdownSendToLobbySeconds() {
        return countdownSendToLobbySeconds;
    }

    /**
     * Return the configured countdown duration for sending players to hardcore.
     */
    public int getCountdownSendToHardcoreSeconds() {
        return countdownSendToHardcoreSeconds;
    }
}
