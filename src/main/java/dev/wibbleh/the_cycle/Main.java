package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

public class Main extends JavaPlugin implements Listener {
    private static final Logger LOG = Logger.getLogger("HardcoreCycle");
    private static final int RPC_QUEUE_DRAIN_INTERVAL_TICKS = 20;
    private static final int PERSISTENT_RPC_RETRY_INTERVAL_TICKS = 1200; // 60 seconds
    private static final int GRACE_PERIOD_TICKS = 60; // 3 seconds
    private static final int AUTO_START_DELAY_TICKS = 40; // 2 seconds
    
    // Title screen timing constants
    private static final long CYCLE_COMPLETE_FADE_IN_MILLIS = 500;
    private static final long CYCLE_COMPLETE_STAY_SECONDS = 2;
    private static final long CYCLE_COMPLETE_FADE_OUT_SECONDS = 1;
    private static final long COUNTDOWN_FADE_IN_MILLIS = 0;
    private static final long COUNTDOWN_STAY_MILLIS = 950;  // Stay for 950ms to ensure smooth transitions without overlap
    private static final long COUNTDOWN_FADE_OUT_MILLIS = 50;  // Quick fade to next countdown
    private static final long CYCLE_START_FADE_IN_MILLIS = 500;
    private static final long CYCLE_START_STAY_SECONDS = 3;
    private static final long CYCLE_START_FADE_OUT_SECONDS = 1;
    
    // Scheduler timing constants
    private static final long COUNTDOWN_INITIAL_DELAY_TICKS = 0L;
    private static final long TICKS_PER_SECOND = 20L;
    
    private final AtomicInteger cycleNumber = new AtomicInteger(1);
    // Attempt counter: number of cycles attempted before beating Minecraft (killing ender dragon)
    private final AtomicInteger attemptsSinceLastWin = new AtomicInteger(0);
    // Total wins counter: number of times the ender dragon has been killed
    private final AtomicInteger totalWins = new AtomicInteger(0);
    // Track if a cycle start request is pending (to avoid duplicate auto-starts)
    private final AtomicBoolean cycleStartPending = new AtomicBoolean(false);
    // Death recap data collected per-cycle
    private final List<Map<String, Object>> deathRecap = new ArrayList<>();
    // Track alive players by UUID
    private final Map<UUID, Boolean> aliveMap = new HashMap<>();
    // Track players who are in the current active cycle (prevents mid-cycle joins)
    private final Set<UUID> playersInCurrentCycle = Collections.synchronizedSet(new HashSet<>());
    private FileConfiguration cfg;
    private boolean enableScoreboard;
    private Objective objective;
    // Lobby configuration: either a server name for Bungee/Velocity or a world name on this server
    private String lobbyServer;
    private String lobbyWorldName;
    private boolean registeredBungeeChannel = false;
    // Cycle tracking
    private File cycleFile;
    // Stats tracking file for attempts and wins
    private File statsFile;
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
    private static final int MAX_RPC_QUEUE = 100;
    private static final int MAX_PERSISTENT_RPC_QUEUE = 100;
    
    // Outbound RPC queue used when the Bungee outgoing channel isn't available yet.
    private final Deque<byte[]> outboundRpcQueue = new ArrayDeque<>();
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
    // Seed configuration: when true, a new random seed will be used for each generated hardcore world
    private boolean randomizeSeed = true;
    // Optional configured seed (only used when randomizeSeed is false and non-zero)
    private long configuredSeed = 0L;
    // Delay (seconds) to wait before starting world generation (configurable)
    private int delayBeforeGenerationSeconds = 3;
    // Maximum seconds to wait for players to leave the previous hardcore world before forcing generation
    private int waitForPlayersToLeaveSeconds = 30;
    // Whether to show a short server-wide pre-generation countdown on the hardcore server
    private boolean preGenerationCountdownEnabled = true;
    // When false, countdown messages are only sent to the command requester (if available); default true
    private boolean countdownBroadcastToAll = true;
    // Path to server.properties file (configurable, default is "server.properties" in current directory)
    private java.nio.file.Path serverPropertiesPath = java.nio.file.Paths.get("server.properties");
    // When true, restart the server after updating server.properties (ensures proper world loading)
    private boolean restartOnCycle = true;
    // Optional UUID of the player who requested the last cycle; used to scope countdown messages when configured
    private volatile UUID lastCycleRequester = null;
    // Pending moves for players who are dead at move time; they will be moved on respawn
    private final Set<UUID> pendingLobbyMoves = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> pendingHardcoreMoves = Collections.synchronizedSet(new HashSet<>());
    // File used to persist pending moves across restarts
    private File pendingMovesFile;
    // File used to persist failed RPC messages across restarts
    private File persistentRpcQueueFile;
    // Persistent queue for failed RPC messages (survives restarts)
    private final List<RpcQueueStorage.QueuedRpc> persistentRpcQueue = Collections.synchronizedList(new ArrayList<>());
    // Task ID for periodic RPC retry task
    private int persistentRpcRetryTaskId = -1;
    // Marker file to indicate a cycle-triggered restart (deleted after processing)
    private File cycleRestartMarkerFile;

    /**
     * Plugin enable lifecycle method. Loads configuration, wires helper services,
     * registers listeners and initializes in-memory state.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();
        
        // Validate configuration early to catch issues before they cause runtime problems
        ConfigValidator.ValidationResult validation = ConfigValidator.validate(cfg);
        validation.logResults();
        if (validation.hasErrors()) {
            LOG.severe("Configuration validation failed. Please fix the errors above and restart the server.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // ensure data folder and pending moves file
        pendingMovesFile = new File(getDataFolder(), "pending_moves.json");
        persistentRpcQueueFile = new File(getDataFolder(), "failed_rpcs.json");
        cycleRestartMarkerFile = new File(getDataFolder(), "cycle_restart.marker");

        // Read server role (default: hardcore). If role is "lobby" the plugin will not create or delete worlds.
        String role = cfg.getString("server.role", "hardcore").trim().toLowerCase(Locale.ROOT);
        isHardcoreBackend = role.equals("hardcore");

        enableScoreboard = cfg.getBoolean("features.scoreboard", true);
        boolean enableActionbarLocal = cfg.getBoolean("features.actionbar", true);
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
        statsFile = new File(getDataFolder(), "stats.txt");
        loadCycleNumber();
        loadStats();
        // wire services
        boolean deletePrev = cfg.getBoolean("behavior.delete_previous_worlds", true);
        boolean deferDelete = cfg.getBoolean("behavior.defer_delete_until_restart", false);
        boolean asyncDelete = cfg.getBoolean("behavior.async_delete", true);
        boolean sharedDeath = cfg.getBoolean("behavior.shared_death", false);

        worldDeletionService = new WorldDeletionService(this, deletePrev, deferDelete, asyncDelete);
        webhookService = new WebhookService(this, webhookUrl);
        var dl = new DeathListener(this, enableActionbarLocal, sharedDeath, aliveMap, deathRecap);
        var edl = new EnderDragonListener(this);
        var pjl = new PlayerJoinListener(this);
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
        // Seed config: default to random seed per-cycle unless configured otherwise
        randomizeSeed = cfg.getBoolean("server.randomize_seed", true);
        configuredSeed = cfg.getLong("server.seed", 0L);
        // Delay and wait settings for safe generation
        delayBeforeGenerationSeconds = cfg.getInt("behavior.delay_before_generation_seconds", 3);
        waitForPlayersToLeaveSeconds = cfg.getInt("behavior.wait_for_players_to_leave_seconds", 30);
        preGenerationCountdownEnabled = cfg.getBoolean("behavior.pre_generation_countdown_enabled", true);
        // Restart on cycle configuration
        restartOnCycle = cfg.getBoolean("behavior.restart_on_cycle", true);
        // Server properties path configuration (relative to server root directory)
        String serverPropsPath = cfg.getString("server.properties_path", "server.properties");
        serverPropertiesPath = java.nio.file.Paths.get(serverPropsPath);
        String httpBind = cfg.getString("server.http_bind", "");
        var rpcHandler = new RpcHandler(this, this, this.rpcSecret, RPC_CHANNEL);
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
        
        // Load persisted RPC queue (if any)
        loadPersistentRpcQueue();

        // Schedule a periodic task to try to drain the outbound RPC queue (runs on main thread)
        if (rpcQueueTaskId == -1) {
            rpcQueueTaskId = Bukkit.getScheduler().runTaskTimer(this, this::drainRpcQueue, RPC_QUEUE_DRAIN_INTERVAL_TICKS, RPC_QUEUE_DRAIN_INTERVAL_TICKS).getTaskId();
        }

        getServer().getPluginManager().registerEvents(dl, this);
        getServer().getPluginManager().registerEvents(edl, this);
        getServer().getPluginManager().registerEvents(pjl, this);

        if (enableScoreboard) {
            var scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            // Use Component-based display name for newer server APIs (avoids deprecated overload)
            objective = scoreboard.registerNewObjective("hc_cycle", "dummy", Component.text("Hardcore Cycle"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            updateScoreboard();
        }

        Bukkit.getOnlinePlayers().forEach(p -> aliveMap.put(p.getUniqueId(), true));

        String roleLabel = isHardcoreBackend ? "hardcore" : "lobby";
        LOG.info("TheCyclePlugin enabled — cycle #" + cycleNumber.get() + "; role=" + roleLabel + ", bungeeRegistered=" + registeredBungeeChannel);
        
        // If this is a hardcore backend that just restarted after a cycle, notify the lobby
        // Check for the marker file that was created before the restart
        if (isHardcoreBackend && restartOnCycle && cycleRestartMarkerFile.exists()) {
            LOG.info("Detected cycle-triggered restart marker; will notify lobby that world is ready.");
            // Delete the marker file immediately to prevent duplicate notifications
            try {
                if (!cycleRestartMarkerFile.delete()) {
                    LOG.warning("Failed to delete cycle restart marker file.");
                }
            } catch (Exception e) {
                LOG.warning("Error deleting cycle restart marker: " + e.getMessage());
            }
            
            // Schedule notification to lobby after a short delay to ensure server is fully ready
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    notifyLobbyWorldReady(cycleNumber.get());
                } catch (Exception e) {
                    LOG.warning("Failed to notify lobby on startup: " + e.getMessage());
                }
            }, 100L); // 5 seconds delay
        }
    }

    /**
     * Plugin disable lifecycle method. Persist cycle number and stats to disk.
     */
    @Override
    public void onDisable() {
        writeCycleFile(cycleNumber.get());
        writeStatsFile();
        savePendingMoves();
        savePersistentRpcQueue();
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
        // Increment attempts counter (will be reset when dragon is killed)
        attemptsSinceLastWin.incrementAndGet();
        writeStatsFile();
        updateScoreboard();
        writeCycleFile(next);

        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            webhookService.send(buildWebhookPayload(next, deathRecap));
        }

        deathRecap.clear();
        // Clear the active cycle players set - new cycle means players can join again
        playersInCurrentCycle.clear();

        // Schedule generation after ensuring all players have been moved to lobby.
        final int cycleNum = next;
        final String prevWorldName = "hardcore_cycle_" + (next - 1);
        // Move ALL online players to lobby before starting world generation (prevents timeouts during generation)
        Collection<? extends Player> playersToMove = Bukkit.getOnlinePlayers();
        if (!playersToMove.isEmpty()) {
            if (preGenerationCountdownEnabled) {
                LOG.info("Scheduling countdown to move all online players to lobby before generating new world (cycle #" + next + ").");
                // Schedule countdown to move players so clients have time to transition. This will mark dead players as pending.
                scheduleCountdownThenSendPlayersToLobby(playersToMove, countdownSendToLobbySeconds);
            } else {
                LOG.info("Moving all online players to lobby immediately before generating new world (pre-generation countdown disabled).");
                for (Player p : playersToMove) {
                    try { sendPlayerToLobby(p); } catch (Exception ex) { LOG.warning("Failed to move player " + p.getName() + " to lobby before generation: " + ex.getMessage()); }
                }
            }
        } else {
            LOG.info("No online players to move to lobby; proceeding with world generation.");
        }

        // Start a short delay before attempting generation, then poll for remaining players leaving worlds
        Bukkit.getScheduler().runTaskLater(this, () -> {
            // If we should wait for players to leave, poll once per second up to the configured timeout
            // Wait if previous world exists and deletion is enabled
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
        
        // Calculate seed if randomization is enabled
        java.util.OptionalLong maybeSeed = SeedUtil.selectSeed(randomizeSeed, configuredSeed);
        String seedStr = "";
        if (maybeSeed.isPresent()) {
            seedStr = String.valueOf(maybeSeed.getAsLong());
            LOG.info("Will use seed " + seedStr + " for world " + newWorldName);
        }
        
        // Update server.properties to set level-name and level-seed
        // This ensures the correct world is loaded on server restart
        try {
            boolean updated = ServerPropertiesUtil.updateLevelNameAndSeed(serverPropertiesPath, newWorldName, seedStr.isEmpty() ? null : seedStr);
            if (updated) {
                LOG.info("Updated server.properties: level-name=" + newWorldName + (seedStr.isEmpty() ? "" : ", level-seed=" + seedStr));
            } else {
                LOG.warning("Failed to update server.properties; server restart may load wrong world.");
            }
        } catch (Exception e) {
            LOG.warning("Error updating server.properties: " + e.getMessage() + "; server restart may load wrong world.");
        }
        
        // If restart_on_cycle is enabled, restart the server instead of creating world in-memory
        if (restartOnCycle) {
            LOG.info("Restart-on-cycle is enabled. Server will restart to load new world: " + newWorldName);
            
            // Show notification to players
            Component title = Component.text("SERVER RESTARTING", NamedTextColor.GOLD);
            Component subtitle = Component.text("New cycle world will be ready soon...", NamedTextColor.YELLOW);
            Title titleScreen = Title.title(
                title,
                subtitle,
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(1)
                )
            );
            
            Bukkit.getOnlinePlayers().forEach(p -> {
                try {
                    p.showTitle(titleScreen);
                    p.sendMessage("§6[HardcoreCycle] Server restarting to load cycle " + next + "...");
                } catch (Exception ignored) {}
            });
            
            // Unload and schedule deletion of previous world before restart
            if (next > 1 && cfg.getBoolean("behavior.delete_previous_worlds", true)) {
                String prevWorldName = "hardcore_cycle_" + (next - 1);
                World prevWorld = Bukkit.getWorld(prevWorldName);
                
                // Unload the world first to avoid save errors during shutdown
                if (prevWorld != null) {
                    LOG.info("Unloading previous world before restart: " + prevWorldName);
                    boolean unloaded = Bukkit.unloadWorld(prevWorld, false);
                    if (!unloaded) {
                        LOG.warning("Failed to unload world " + prevWorldName + " before restart.");
                    }
                }
                
                // Schedule deletion (will happen after unload or on next startup if deferred)
                worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
            }
            
            // Create marker file to indicate this is a cycle-triggered restart
            try {
                if (cycleRestartMarkerFile.createNewFile()) {
                    LOG.info("Created cycle restart marker file.");
                } else {
                    LOG.warning("Cycle restart marker file already exists.");
                }
            } catch (Exception e) {
                LOG.warning("Failed to create cycle restart marker: " + e.getMessage());
            }
            
            // Schedule server restart after a short delay to allow world unload and cleanup
            Bukkit.getScheduler().runTaskLater(this, () -> {
                LOG.info("Initiating server restart for new world generation...");
                // Restart the server - this works with most server management scripts (like start.sh with restart loop)
                Bukkit.getServer().shutdown();
            }, 100L); // 5 seconds delay to allow cleanup
            
            return;
        }
        
        // Legacy mode: create world in-memory (may not work correctly if default world was deleted)
        LOG.info("Creating world in-memory (legacy mode). For best reliability, enable restart_on_cycle in config.");
        
        // Show title to all online players indicating new cycle is starting
        Component title = Component.text("CYCLE " + next, NamedTextColor.GREEN);
        Component subtitle = Component.text("Generating new world...", NamedTextColor.GRAY);
        Title titleScreen = Title.title(
            title,
            subtitle,
            Title.Times.times(
                Duration.ofMillis(500),  // fade in
                Duration.ofSeconds(3),    // stay
                Duration.ofSeconds(1)     // fade out
            )
        );
        
        Bukkit.getOnlinePlayers().forEach(p -> { 
            try { 
                p.showTitle(titleScreen);
                p.sendMessage("[HardcoreCycle] Generating world: " + newWorldName); 
            } catch (Exception ignored) {} 
        });

        World newWorld = null;
        try {
            // Build WorldCreator and apply seed strategy using SeedUtil
            org.bukkit.WorldCreator wc = new org.bukkit.WorldCreator(newWorldName);
            if (maybeSeed.isPresent()) {
                long seed = maybeSeed.getAsLong();
                wc.seed(seed);
                LOG.info("Using seed " + seed + " for world " + newWorldName);
            }
            newWorld = Bukkit.createWorld(wc);
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
                    try { 
                        p.teleport(spawn);
                        // Player will be added to current cycle in PlayerJoinListener.onPlayerChangedWorld
                    } catch (Exception ex) { LOG.warning("Failed to teleport player " + p.getName() + " to new world: " + ex.getMessage()); }
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

        // Show premium title notification for world cycle completion
        showWorldCycleCompleteTitle(next);

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
            try (var r = new BufferedReader(new FileReader(cycleFile))) {
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
            try (var w = new BufferedWriter(new FileWriter(cycleFile))) {
                w.write(String.valueOf(n));
            }
        } catch (Exception e) {
            LOG.severe("Unable to write cycle file: " + e.getMessage());
        }
    }

    /**
     * Load stats (attempts and wins) from disk. If the file is missing or invalid, defaults to 0.
     */
    private void loadStats() {
        try {
            ensureDataFolderExists();
            if (!statsFile.exists()) {
                writeStatsFile();
                return;
            }
            try (var r = new BufferedReader(new FileReader(statsFile))) {
                String attemptsLine = r.readLine();
                String winsLine = r.readLine();
                if (attemptsLine != null && !attemptsLine.trim().isEmpty()) {
                    attemptsSinceLastWin.set(Integer.parseInt(attemptsLine.trim()));
                }
                if (winsLine != null && !winsLine.trim().isEmpty()) {
                    totalWins.set(Integer.parseInt(winsLine.trim()));
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to read stats file, defaulting to 0: " + e.getMessage());
        }
    }

    /**
     * Persist stats (attempts and wins) to the plugin data folder (stats.json).
     */
    private void writeStatsFile() {
        try {
            ensureDataFolderExists();
            try (var w = new BufferedWriter(new FileWriter(statsFile))) {
                w.write(String.valueOf(attemptsSinceLastWin.get()));
                w.newLine();
                w.write(String.valueOf(totalWins.get()));
            }
        } catch (Exception e) {
            LOG.severe("Unable to write stats file: " + e.getMessage());
        }
    }

    /**
     * Update the server scoreboard with the current cycle number, attempts, and wins when enabled.
     */
    private void updateScoreboard() {
        if (!enableScoreboard || objective == null) return;
        objective.getScore("Cycle:").setScore(cycleNumber.get());
        objective.getScore("Attempts:").setScore(attemptsSinceLastWin.get());
        objective.getScore("Wins:").setScore(totalWins.get());
    }

    /**
     * Show a premium title notification when a world cycle is complete.
     */
    private void showWorldCycleCompleteTitle(int cycleNum) {
        Component title = Component.text("CYCLE " + cycleNum, NamedTextColor.GOLD);
        Component subtitle = Component.text("Ready to Play!", NamedTextColor.GREEN);
        Title titleScreen = Title.title(
            title,
            subtitle,
            Title.Times.times(
                Duration.ofMillis(CYCLE_COMPLETE_FADE_IN_MILLIS),
                Duration.ofSeconds(CYCLE_COMPLETE_STAY_SECONDS),
                Duration.ofSeconds(CYCLE_COMPLETE_FADE_OUT_SECONDS)
            )
        );
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.showTitle(titleScreen);
            } catch (Exception e) {
                LOG.warning("Failed to show cycle complete title to " + p.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Create shared title times for countdown displays.
     * @return Title.Times configured for countdown animations
     */
    private static Title.Times createCountdownTitleTimes() {
        return Title.Times.times(
            Duration.ofMillis(COUNTDOWN_FADE_IN_MILLIS),
            Duration.ofMillis(COUNTDOWN_STAY_MILLIS),
            Duration.ofMillis(COUNTDOWN_FADE_OUT_MILLIS)
        );
    }

    /**
     * Build a JSON payload for the webhook based on the death recap.
     *
     * @param cycleNum cycle number
     * @param recap    death recap list to include
     * @return JSON string payload
     */
    private String buildWebhookPayload(int cycleNum, List<Map<String, Object>> recap) {
        var sb = new StringBuilder(512); // Pre-allocate reasonable initial capacity
        sb.append("{\"content\":null,\"embeds\":[{\"title\":\"Hardcore cycle ")
                .append(cycleNum)
                .append(" complete\",\"description\":\"Server generated new world for cycle #")
                .append(cycleNum)
                .append("\",\"fields\":[");

        for (int i = 0; i < recap.size(); i++) {
            var e = recap.get(i);
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
     * Attempts to switch a dead player to spectator mode to allow teleportation.
     * 
     * @param p the player to switch to spectator mode
     * @return true if player was successfully switched to spectator or was not dead, false if switch failed
     */
    private boolean switchDeadPlayerToSpectator(Player p) {
        if (p == null || !p.isDead()) {
            return false; // Player is not dead, no action needed
        }
        
        try {
            p.setGameMode(org.bukkit.GameMode.SPECTATOR);
            LOG.info("Player " + p.getName() + " was dead; switched to spectator mode for teleportation.");
            return false;
        } catch (Exception e) {
            LOG.warning("Failed to switch dead player to spectator mode: " + e.getMessage());
            return true;
        }
    }

    /**
     * Send a player to the configured lobby. Priority:
     * 1) If lobbyServer is configured and Bungee is registered, send a Bungee Connect message.
     * 2) Else if lobbyWorldName exists on this server, teleport the player there.
     * 3) Otherwise log a warning and leave the player in place.
     *
     * @param p player to move to lobby
     */
    public boolean sendPlayerToLobby(Player p) {
        if (p == null) return false;
        if (p.isDead()) {
            if (switchDeadPlayerToSpectator(p)) {
                // Fallback: mark pending move and wait for respawn
                pendingLobbyMoves.add(p.getUniqueId());
                savePendingMovesAsync();
                LOG.info("Player " + p.getName() + " is dead; will send to lobby on respawn.");
                return true;
            }
        }
        if (!lobbyServer.isEmpty() && registeredBungeeChannel) {
            try (var outputStream = new ByteArrayOutputStream();
                 var out = new DataOutputStream(outputStream)) {
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
            var lw = Bukkit.getWorld(lobbyWorldName);
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
             String caller = requester instanceof org.bukkit.entity.Player ? ((org.bukkit.entity.Player) requester).getUniqueId().toString() : "console";
             String payload = "{\"action\":\"" + action + "\",\"caller\":\"" + caller + "\"}";
             
             try {
                 String sig = RpcHttpUtil.computeHmacHex(rpcSecret, payload);
                 
                 // Use HttpRetryUtil for resilient HTTP POST with automatic retry and exponential backoff
                 HttpRetryUtil.RetryConfig retryConfig = HttpRetryUtil.RetryConfig.defaults();
                 HttpRetryUtil.HttpResult result = HttpRetryUtil.postWithRetry(hardcoreHttpUrl, payload, sig, retryConfig);
                 
                 if (result.success()) {
                     LOG.info("Forwarded RPC via HTTP to " + hardcoreHttpUrl + " status=" + result.statusCode() + " (attempts=" + result.attempts() + ")");
                     return true;
                 } else {
                     LOG.warning("HTTP RPC forward failed after " + result.attempts() + " attempts: " + result.errorMessage());
                     // Save to persistent queue for retry on next startup or periodic retry
                     enqueuePersistentRpc(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), action, caller);
                 }
             } catch (Exception e) {
                 LOG.warning("HTTP RPC forward setup failed: " + e.getMessage());
                 // Save to persistent queue for retry
                 enqueuePersistentRpc(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), action, caller);
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
            if (switchDeadPlayerToSpectator(p)) {
                // Fallback: mark pending move and wait for respawn
                pendingHardcoreMoves.add(p.getUniqueId());
                savePendingMovesAsync();
                LOG.info("Player " + p.getName() + " is dead; will move to hardcore on respawn.");
                return true;
            }
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
     * Enqueue a failed RPC to the persistent queue for retry on next startup.
     * This ensures RPC messages are not lost during server restarts.
     */
    private synchronized void enqueuePersistentRpc(byte[] payload, String action, String caller) {
        if (payload == null || payload.length == 0) return;
        if (persistentRpcQueue.size() >= MAX_PERSISTENT_RPC_QUEUE) {
            persistentRpcQueue.remove(0);
            LOG.warning("Persistent RPC queue full; dropping oldest message.");
        }
        long timestamp = java.time.Instant.now().getEpochSecond();
        RpcQueueStorage.QueuedRpc rpc = new RpcQueueStorage.QueuedRpc(payload, action, caller, timestamp, 0);
        persistentRpcQueue.add(rpc);
        LOG.info("Enqueued persistent RPC; queue size=" + persistentRpcQueue.size());
        // Save immediately to ensure it survives unexpected shutdown
        savePersistentRpcQueue();
    }

    /**
     * Load persistent RPC queue from disk on startup.
     */
    private void loadPersistentRpcQueue() {
        if (persistentRpcQueueFile == null || !persistentRpcQueueFile.exists()) {
            LOG.info("No persistent RPC queue file found; starting with empty queue.");
            return;
        }
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(persistentRpcQueueFile);
        persistentRpcQueue.addAll(loaded);
        LOG.info("Loaded " + loaded.size() + " persistent RPCs from disk.");
        
        // Start periodic retry task if we have queued messages
        if (!persistentRpcQueue.isEmpty()) {
            schedulePeriodicRpcRetry();
        }
    }

    /**
     * Save persistent RPC queue to disk.
     */
    private void savePersistentRpcQueue() {
        if (persistentRpcQueueFile == null) return;
        RpcQueueStorage.save(persistentRpcQueueFile, new ArrayList<>(persistentRpcQueue));
    }

    /**
     * Schedule periodic retry of persistent RPC queue.
     * Runs every 60 seconds to attempt delivery of failed RPCs.
     */
    private void schedulePeriodicRpcRetry() {
        // Cancel existing task if any
        if (persistentRpcRetryTaskId != -1) {
            Bukkit.getScheduler().cancelTask(persistentRpcRetryTaskId);
            LOG.info("Cancelled existing RPC retry task.");
        }
        
        // Schedule task to run every 60 seconds
        persistentRpcRetryTaskId = Bukkit.getScheduler().runTaskTimer(this, this::retryPersistentRpcQueue, PERSISTENT_RPC_RETRY_INTERVAL_TICKS, PERSISTENT_RPC_RETRY_INTERVAL_TICKS).getTaskId();
        LOG.info("Scheduled periodic RPC retry task (every 60 seconds).");
    }

    /**
     * Attempt to retry RPCs from the persistent queue.
     */
    private synchronized void retryPersistentRpcQueue() {
        if (persistentRpcQueue.isEmpty()) return;
        
        String hardcoreHttpUrl = cfg.getString("server.hardcore_http_url", "").trim();
        if (hardcoreHttpUrl.isEmpty()) {
            LOG.fine("No hardcore HTTP URL configured; skipping persistent RPC retry.");
            return;
        }

        LOG.info("Retrying " + persistentRpcQueue.size() + " persistent RPCs...");
        var toRemove = new ArrayList<RpcQueueStorage.QueuedRpc>();
        var toUpdate = new ArrayList<RpcQueueStorage.QueuedRpc>();
        
        for (var rpc : persistentRpcQueue) {
            if (rpc.isExpired()) {
                toRemove.add(rpc);
                LOG.info("Removing expired RPC: action=" + rpc.action());
                continue;
            }

            try {
                String payload = new String(rpc.payload(), java.nio.charset.StandardCharsets.UTF_8);
                String sig = RpcHttpUtil.computeHmacHex(rpcSecret, payload);
                
                var retryConfig = HttpRetryUtil.RetryConfig.noRetry();
                var result = HttpRetryUtil.postWithRetry(hardcoreHttpUrl, payload, sig, retryConfig);
                
                if (result.success()) {
                    LOG.info("Persistent RPC retry succeeded: action=" + rpc.action());
                    toRemove.add(rpc);
                } else {
                    LOG.fine("Persistent RPC retry failed: action=" + rpc.action() + " - will retry later");
                    // Mark for attempt count update
                    toUpdate.add(rpc);
                }
            } catch (Exception e) {
                LOG.warning("Error retrying persistent RPC: " + e.getMessage());
            }
        }

        // Apply updates in a single pass
        for (var rpc : toUpdate) {
            int index = persistentRpcQueue.indexOf(rpc);
            if (index >= 0) {
                persistentRpcQueue.set(index, rpc.withIncrementedAttempts());
            }
        }

        persistentRpcQueue.removeAll(toRemove);
        if (!toRemove.isEmpty() || !toUpdate.isEmpty()) {
            savePersistentRpcQueue();
            LOG.info("Removed " + toRemove.size() + " RPCs from persistent queue; " + persistentRpcQueue.size() + " remaining.");
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

        try {
            String sig = RpcHttpUtil.computeHmacHex(rpcSecret, payload);
            
            // Use HttpRetryUtil for resilient HTTP POST with retry
            HttpRetryUtil.RetryConfig retryConfig = HttpRetryUtil.RetryConfig.defaults();
            HttpRetryUtil.HttpResult result = HttpRetryUtil.postWithRetry(lobbyUrl, payload, sig, retryConfig);
            
            if (result.success()) {
                LOG.info("Notified lobby of world-ready: " + lobbyUrl + " status=" + result.statusCode() + " (attempts=" + result.attempts() + ")");
            } else {
                LOG.warning("Failed to notify lobby of world-ready after " + result.attempts() + " attempts: " + result.errorMessage());
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
            players.forEach(this::sendPlayerToLobby);
            return;
        }
        // Determine effective target players based on broadcast config and last requester
        Collection<? extends Player> targets;
        if (!countdownBroadcastToAll && lastCycleRequester != null) {
            var req = Bukkit.getPlayer(lastCycleRequester);
            if (req != null && players.contains(req)) targets = List.of(req);
            else targets = players;
        } else {
            targets = players;
        }
        // Add all targets to pending moves so respawn will trigger a move if they're dead when countdown ends
        targets.forEach(p -> { if (p != null) pendingLobbyMoves.add(p.getUniqueId()); });
        savePendingMovesAsync();
        final int total = seconds;
        // Create static title component once outside the loop for reuse
        final Component lobbyTitleStatic = Component.text("Returning to Lobby", NamedTextColor.YELLOW);
        final Title.Times titleTimes = createCountdownTitleTimes();
        new org.bukkit.scheduler.BukkitRunnable() {
             int remaining = total;
             @Override
             public void run() {
                 if (remaining <= 0) {
                    for (var p : targets) {
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
                     clearLastCycleRequester();
                     cancel();
                      return;
                 }
                // Show premium title countdown - create subtitle once per iteration
                Component subtitle = Component.text(remaining + "s", NamedTextColor.WHITE);
                Title titleScreen = Title.title(lobbyTitleStatic, subtitle, titleTimes);
                Component actionBarMsg = Component.text("Lobby transfer in " + remaining + "s", NamedTextColor.GOLD);
                
                for (var p : targets) {
                    if (p == null) continue;
                    try {
                        p.showTitle(titleScreen);
                        // Also send action bar for less intrusive reminder
                        p.sendActionBar(actionBarMsg);
                    } catch (Exception ignored) {}
                }
                remaining--;
            }
        }.runTaskTimer(this, COUNTDOWN_INITIAL_DELAY_TICKS, TICKS_PER_SECOND);
    }

    /**
     * Schedule a countdown in chat on the lobby and then move players to the configured hardcore server.
     */
    public void scheduleCountdownThenMovePlayersToHardcore(int seconds) {
        // Clear the cycle start pending flag since world is now ready
        clearCycleStartPending();
        
        String target = getHardcoreServerName();
        if (target == null || target.isEmpty()) {
            LOG.warning("Hardcore server name not configured; cannot move players to hardcore.");
            return;
        }
        if (seconds <= 0) {
            for (var p : Bukkit.getOnlinePlayers()) {
                showCycleStartTitle(p);
                sendPlayerToServer(p, target);
            }
            return;
        }
        // Determine targets depending on broadcast config and last requester
        Collection<? extends Player> targets;
        if (!countdownBroadcastToAll && lastCycleRequester != null) {
            var req = Bukkit.getPlayer(lastCycleRequester);
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
        targets.forEach(p -> { if (p != null) pendingHardcoreMoves.add(p.getUniqueId()); });
        savePendingMovesAsync();
        final int total = seconds;
        // Create static title component once outside the loop for reuse
        final Component hardcoreTitleStatic = Component.text("Entering Hardcore", NamedTextColor.RED);
        final Title.Times titleTimes = createCountdownTitleTimes();
        new org.bukkit.scheduler.BukkitRunnable() {
            int remaining = total;
            @Override
            public void run() {
                if (remaining <= 0) {
                    for (var p : targets) {
                        if (p == null) continue;
                        if (p.isDead()) {
                            LOG.info("Player " + p.getName() + " still dead at hardcore countdown end; will move on respawn.");
                        } else {
                            pendingHardcoreMoves.remove(p.getUniqueId());
                            savePendingMovesAsync();
                            showCycleStartTitle(p);
                            sendPlayerToServer(p, target);
                        }
                    }
                    clearLastCycleRequester();
                    cancel();
                     return;
                 }
                // Show premium title countdown - create subtitle once per iteration
                Component subtitle = Component.text(remaining + "s", NamedTextColor.WHITE);
                Title titleScreen = Title.title(hardcoreTitleStatic, subtitle, titleTimes);
                Component actionBarMsg = Component.text("Hardcore transfer in " + remaining + "s", NamedTextColor.GOLD);
                
                for (var p : targets) {
                    if (p == null) continue;
                    try {
                        p.showTitle(titleScreen);
                        // Also send action bar for less intrusive reminder
                        p.sendActionBar(actionBarMsg);
                    } catch (Exception ignored) {}
                }
                remaining--;
            }
        }.runTaskTimer(this, COUNTDOWN_INITIAL_DELAY_TICKS, TICKS_PER_SECOND);
    }

    /**
     * Show a large title screen to a player when they're about to enter a new cycle.
     * Public wrapper method for external access (e.g., from PlayerJoinListener).
     */
    public void showCycleStartTitleToPlayer(Player p) {
        showCycleStartTitle(p);
    }

    /**
     * Show a large title screen to a player when they're about to enter a new cycle.
     */
    private void showCycleStartTitle(Player p) {
        if (p == null) return;
        try {
            Component title = Component.text("CYCLE " + cycleNumber.get(), NamedTextColor.GOLD);
            Component subtitle = Component.text("Good luck!", NamedTextColor.YELLOW);
            Title titleScreen = Title.title(
                title,
                subtitle,
                Title.Times.times(
                    Duration.ofMillis(CYCLE_START_FADE_IN_MILLIS),
                    Duration.ofSeconds(CYCLE_START_STAY_SECONDS),
                    Duration.ofSeconds(CYCLE_START_FADE_OUT_SECONDS)
                )
            );
            p.showTitle(titleScreen);
        } catch (Exception e) {
            LOG.warning("Failed to show cycle start title to " + p.getName() + ": " + e.getMessage());
        }
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

    /**
     * Record an ender dragon kill: increment wins counter, reset attempts counter.
     */
    public void recordDragonKill() {
        totalWins.incrementAndGet();
        attemptsSinceLastWin.set(0);
        writeStatsFile();
        updateScoreboard();
        LOG.info("Dragon kill recorded! Total wins: " + totalWins.get() + ", attempts reset to 0.");
    }

    /**
     * Get the current number of attempts since the last dragon kill.
     */
    public int getAttemptsSinceLastWin() {
        return attemptsSinceLastWin.get();
    }

    /**
     * Get the total number of dragon kills (wins).
     */
    public int getTotalWins() {
        return totalWins.get();
    }

    /**
     * Check if a player is in the current active cycle.
     */
    public boolean isPlayerInCurrentCycle(UUID playerId) {
        return playersInCurrentCycle.contains(playerId);
    }

    /**
     * Add a player to the current active cycle.
     */
    public void addPlayerToCurrentCycle(UUID playerId) {
        playersInCurrentCycle.add(playerId);
    }

    /**
     * Check if we should auto-start a new cycle on the lobby server.
     * This is called when players join the lobby and there's no active cycle.
     * Only starts a cycle if not already pending and if configured to auto-start.
     */
    public void checkAndAutoStartCycle() {
        // Only run on lobby servers
        if (isHardcoreBackend) {
            return;
        }

        // Use atomic compareAndSet to ensure only one thread can start a cycle at a time
        if (!cycleStartPending.compareAndSet(false, true)) {
            LOG.info("Cycle start already pending, skipping auto-start check.");
            return;
        }

        // Check if there are players waiting in lobby
        long playersInLobby = Bukkit.getOnlinePlayers().stream()
            .filter(p -> !p.getWorld().getName().startsWith("hardcore_cycle_"))
            .count();

        if (playersInLobby == 0) {
            LOG.info("No players in lobby, skipping auto-start.");
            cycleStartPending.set(false);
            return;
        }

        LOG.info("Auto-starting new cycle - " + playersInLobby + " player(s) waiting in lobby.");

        // Send RPC to hardcore to trigger a new cycle
        boolean forwarded = sendRpcToHardcore("cycle-now", null);
        if (!forwarded) {
            // RPC failed - clear the pending flag so we can retry later
            LOG.warning("Failed to auto-start cycle - RPC forwarding failed. Will retry on next player join.");
            cycleStartPending.set(false);
            return;
        }
        
        // RPC forwarded successfully - notify players
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (!p.getWorld().getName().startsWith("hardcore_cycle_")) {
                p.sendMessage("§aA new cycle is starting! Please wait while the world is being generated...");
            }
        });
        // Flag will be cleared when world becomes ready via clearCycleStartPending()
    }

    /**
     * Clear the cycle start pending flag (called when world is ready or on error).
     */
    public void clearCycleStartPending() {
        cycleStartPending.set(false);
    }
}
