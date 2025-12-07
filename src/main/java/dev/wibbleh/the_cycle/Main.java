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

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main extends JavaPlugin implements Listener {
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
    private boolean registeredRpcChannel = false;
    // Outbound RPC queue used when the Bungee outgoing channel isn't available yet.
    private final Deque<byte[]> outboundRpcQueue = new ArrayDeque<>();
    private static final int MAX_RPC_QUEUE = 100;
    private int rpcQueueTaskId = -1;
 // Webhook
     private String webhookUrl;
     private WorldDeletionService worldDeletionService;
     private WebhookService webhookService;
     private CommandHandler commandHandler;
    // Optional embedded HTTP RPC server (only started on hardcore backends when configured)
    private HttpRpcServer httpRpcServer;

    /**
     * Plugin enable lifecycle method. Loads configuration, wires helper services,
     * registers listeners and initializes in-memory state.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();

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
            if (getServer() != null) {
                if (!getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord")) {
                    getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
                }
                registeredBungeeChannel = getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord");
            }
        } catch (Exception e) {
            getLogger().warning("Could not register BungeeCord outgoing channel; cross-server lobby will not work. Reason: " + e.getMessage());
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
        if (getCommand("cycle") != null) {
            getCommand("cycle").setExecutor(commandHandler);
            getCommand("cycle").setTabCompleter(commandHandler);
        } else {
            getLogger().warning("Command 'cycle' not defined in plugin.yml; tab-complete and execution will not be available.");
        }

        // Register RPC handler: incoming channel on hardcore, and provide outgoing registration for lobby
        this.rpcSecret = cfg.getString("server.rpc_secret", "");
        this.hardcoreServerName = cfg.getString("server.hardcore", "");
        // Optional HTTP RPC URL for lobby to call (if present, prefer HTTP forwarding when available)
        String hardcoreHttpUrl = cfg.getString("server.hardcore_http_url", "").trim();
        int httpPort = cfg.getInt("server.http_port", 8080);
        String httpBind = cfg.getString("server.http_bind", "");
        RpcHandler rpcHandler = new RpcHandler(this, this, this.rpcSecret, RPC_CHANNEL);
        try {
            // Incoming channel
            getServer().getMessenger().registerIncomingPluginChannel(this, RPC_CHANNEL, rpcHandler);
            // Outgoing channel (used by lobby instances to forward RPCs)
            getServer().getMessenger().registerOutgoingPluginChannel(this, RPC_CHANNEL);
            registeredRpcChannel = true;
            getLogger().info("Registered RPC plugin channel: " + RPC_CHANNEL + " (role=" + (isHardcoreBackend ? "hardcore" : "lobby") + ")");
        } catch (Exception ex) {
            registeredRpcChannel = false;
            getLogger().warning("Failed to register " + RPC_CHANNEL + " plugin channels: " + ex.getMessage());
        }

        // If we're configured as the hardcore backend and an HTTP server is desired, start embedded HTTP RPC server
        try {
            if (isHardcoreBackend && cfg.getBoolean("server.http_enabled", false)) {
                httpRpcServer = new HttpRpcServer(this, httpPort, httpBind);
                httpRpcServer.start();
                getLogger().info("Started embedded HTTP RPC server on port " + httpPort + (httpBind.isEmpty() ? "" : " bound to " + httpBind));
            }
        } catch (Exception e) {
            getLogger().warning("Failed to start embedded HTTP RPC server: " + e.getMessage());
            httpRpcServer = null;
        }

        worldDeletionService.processPendingDeletions();

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
            objective = scoreboard.registerNewObjective("hc_cycle", "dummy", "Hardcore Cycle");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            updateScoreboard();
        }

        Bukkit.getOnlinePlayers().forEach(p -> aliveMap.put(p.getUniqueId(), true));

        getLogger().info("TheCyclePlugin enabled — cycle #" + cycleNumber.get() + "; role=" + (isHardcoreBackend ? "hardcore" : "lobby") + ", bungeeRegistered=" + registeredBungeeChannel + ", rpcRegistered=" + registeredRpcChannel);
    }

    /**
     * Plugin disable lifecycle method. Persist cycle number to disk.
     */
    @Override
    public void onDisable() {
        writeCycleFile(cycleNumber.get());
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
                        getLogger().warning("World cycle attempted on a server configured as 'lobby'. This server will not create worlds. Please run /cycle on your hardcore backend.");
            return;
        }
        int next = cycleNumber.incrementAndGet();
        updateScoreboard();
        writeCycleFile(next);

        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            webhookService.send(buildWebhookPayload(next, deathRecap));
        }

        deathRecap.clear();

        String newWorldName = "hardcore_cycle_" + next;
        getLogger().info("Generating world: " + newWorldName);
        World newWorld = null;
        try {
            newWorld = Bukkit.createWorld(new org.bukkit.WorldCreator(newWorldName));
        } catch (Exception e) {
            getLogger().warning("Failed to create new world '" + newWorldName + "': " + e.getMessage());
        }

        if (next > 1 && cfg.getBoolean("behavior.delete_previous_worlds", true)) {
            String prevWorldName = "hardcore_cycle_" + (next - 1);
            World prevWorld = Bukkit.getWorld(prevWorldName);
            if (prevWorld != null) {
                // SAFEGUARD: teleport any players still inside the previous world to the new world's spawn (or to lobby if new world isn't available)
                if (newWorld != null) {
                    try {
                        final org.bukkit.Location spawn = newWorld.getSpawnLocation();
                        if (spawn != null) {
                            for (Player p : prevWorld.getPlayers()) {
                                try {
                                    p.teleport(spawn);
                                } catch (Exception ex) {
                                    getLogger().warning("Failed to teleport player " + p.getName() + " out of " + prevWorldName + ": " + ex.getMessage());
                                }
                                aliveMap.put(p.getUniqueId(), true);
                            }
                        } else {
                            // spawn is null — fall back to lobby
                            for (Player p : prevWorld.getPlayers()) sendPlayerToLobby(p);
                        }
                        getLogger().info("Teleported players out of previous world: " + prevWorldName + " to new spawn.");
                    } catch (Exception ex) {
                        getLogger().warning("Error while teleporting players from previous world: " + ex.getMessage());
                    }
                } else {
                    getLogger().warning("New world is null; teleporting players from " + prevWorldName + " to lobby instead.");
                    for (Player p : prevWorld.getPlayers()) sendPlayerToLobby(p);
                }

                getLogger().info("Unloading previous world: " + prevWorldName);
                boolean unloaded = Bukkit.unloadWorld(prevWorld, false);
                if (!unloaded) {
                    getLogger().warning("Failed to unload world " + prevWorldName + "; scheduling deletion fallback.");
                    worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
                } else {
                    worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
                }
            } else {
                // World file may still exist on disk even if not loaded; attempt deletion of folder anyway
                worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
            }
        }

        if (newWorld != null) {
            final org.bukkit.Location spawn = newWorld.getSpawnLocation();
            if (spawn != null) {
                Bukkit.getOnlinePlayers().forEach(p -> {
                    try {
                        p.teleport(spawn);
                    } catch (Exception ex) {
                        getLogger().warning("Failed to teleport player " + p.getName() + " to new world: " + ex.getMessage());
                    }
                    aliveMap.put(p.getUniqueId(), true);
                });
            } else {
                // New world exists but spawn is null / unavailable — send players to lobby
                getLogger().warning("New world spawn is null; sending players to configured lobby (if any).");
                Bukkit.getOnlinePlayers().forEach(this::sendPlayerToLobby);
            }
        } else {
            getLogger().warning("New world is null; sending players to configured lobby (if any).");
            Bukkit.getOnlinePlayers().forEach(this::sendPlayerToLobby);
        }

        pushBossbar("World cycled — welcome to cycle #" + next);

        getLogger().info("Cycle " + next + " complete.");
    }

    /**
     * Ensure the plugin data folder exists. If creation fails a warning is logged.
     */
    private void ensureDataFolderExists() {
        File df = getDataFolder();
        if (!df.exists()) {
            try {
                if (!df.mkdirs()) getLogger().warning("Failed to create data folder: " + df.getAbsolutePath());
            } catch (Exception e) {
                getLogger().warning("Failed to create data folder: " + e.getMessage());
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
            getLogger().warning("Failed to read cycle file, defaulting to 1: " + e.getMessage());
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
            getLogger().severe("Unable to write cycle file: " + e.getMessage());
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
    private void sendPlayerToLobby(Player p) {
        if (p == null) return;
        if (!lobbyServer.isEmpty() && registeredBungeeChannel) {
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                 DataOutputStream out = new DataOutputStream(outputStream)) {
                out.writeUTF("Connect");
                out.writeUTF(lobbyServer);
                p.sendPluginMessage(this, "BungeeCord", outputStream.toByteArray());
                getLogger().info("Sent player " + p.getName() + " to lobby server: " + lobbyServer);
                return;
            } catch (Exception e) {
                getLogger().warning("Failed to send player to Bungee lobby: " + e.getMessage());
            }
        }

        if (!lobbyWorldName.isEmpty()) {
            World lw = Bukkit.getWorld(lobbyWorldName);
            if (lw != null) {
                try {
                    p.teleport(lw.getSpawnLocation());
                    aliveMap.put(p.getUniqueId(), true);
                    getLogger().info("Teleported player " + p.getName() + " to lobby world: " + lobbyWorldName);
                    return;
                } catch (Exception e) {
                    getLogger().warning("Failed to teleport player to lobby world: " + e.getMessage());
                }
            } else {
                getLogger().warning("Configured lobby world '" + lobbyWorldName + "' not found on this server.");
            }
        }

        getLogger().warning("No lobby configured; cannot move player " + p.getName() + ".");
    }

    /**
     * Handle plugin commands by delegating to the CommandHandler.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
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
         if (hardcoreServerName == null || hardcoreServerName.isEmpty()) {
             getLogger().warning("Hardcore server name not configured; cannot forward RPC.");
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
                     getLogger().info("Forwarded RPC via HTTP to " + hardcoreHttpUrl + " status=" + code);
                     return true;
                 } else {
                     getLogger().warning("HTTP RPC forward returned non-2xx: " + code);
                 }
             } catch (Exception e) {
                 getLogger().warning("HTTP RPC forward failed: " + e.getMessage());
             }
         }

         // Determine player to send plugin message through
         org.bukkit.entity.Player through = null;
         if (requester instanceof org.bukkit.entity.Player) through = (org.bukkit.entity.Player) requester;
         else {
             for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) { through = p; break; }
         }
         if (through == null) {
             getLogger().warning("No online player to send plugin message; cannot forward RPC to hardcore.");
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
                        getLogger().warning("Bungee outgoing channel registration did not take effect; cannot forward RPC.");
                        return false;
                    }
                } else {
                    // No server available (likely in unit test environment) — proceed without registration.
                }
            } catch (Exception e) {
                getLogger().warning("Failed to register Bungee outgoing channel; cannot forward RPC: " + e.getMessage());
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
                    getLogger().warning("Send failed due to unregistered channel; enqueueing RPC for retry: " + iae.getMessage());
                    enqueueOutboundRpc(outStream.toByteArray());
                    return true; // treat as accepted (queued)
                } catch (Exception ex) {
                    getLogger().warning("Send failed; enqueueing RPC for retry: " + ex.getMessage());
                    enqueueOutboundRpc(outStream.toByteArray());
                    return true;
                }
             }

             getLogger().info("Forwarded RPC action '" + action + "' to hardcore server: " + hardcoreServerName);
             return true;
         } catch (Exception e) {
             getLogger().warning("Failed to forward RPC to hardcore: " + e.getMessage());
             return false;
         }
     }

    /**
     * Enqueue an outbound RPC packet (outer Bungee Forward packet) for later delivery.
     * If the queue is full the oldest item is discarded to make room.
     * This method is synchronized to be safe across threads (though callers should be on main thread).
     *
     * @param packet outer packet bytes to send later
     */
    private synchronized void enqueueOutboundRpc(byte[] packet) {
        if (packet == null || packet.length == 0) return;
        if (outboundRpcQueue.size() >= MAX_RPC_QUEUE) {
            // drop oldest to avoid unbounded memory growth
            outboundRpcQueue.pollFirst();
            getLogger().warning("Outbound RPC queue full; dropping oldest queued RPC.");
        }
        outboundRpcQueue.addLast(packet);
        getLogger().info("Enqueued RPC for later delivery; queue size=" + outboundRpcQueue.size());
    }

    /**
     * Drain the outbound RPC queue, attempting to send queued packets when the Bungee channel is available.
     * Runs on the main server thread via a scheduled task.
     */
    private synchronized void drainRpcQueue() {
        if (outboundRpcQueue.isEmpty()) return;
        // Ensure outgoing channel is registered
        if (!registeredBungeeChannel) {
            try {
                if (getServer() != null) {
                    if (!getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord")) {
                        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
                    }
                    registeredBungeeChannel = getServer().getMessenger().isOutgoingChannelRegistered(this, "BungeeCord");
                }
            } catch (Exception e) {
                getLogger().warning("Periodic RPC drain: failed to register Bungee outgoing channel: " + e.getMessage());
                return;
            }
        }

        if (!registeredBungeeChannel) return;

        // Find a player to send through
        org.bukkit.entity.Player through = null;
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) { through = p; break; }
        if (through == null) return;

        // Try to send queued packets; stop on first failure to avoid tight retry loops
        while (!outboundRpcQueue.isEmpty()) {
            byte[] pkt = outboundRpcQueue.peekFirst();
            if (pkt == null) { outboundRpcQueue.pollFirst(); continue; }
            try {
                through.sendPluginMessage(this, "BungeeCord", pkt);
                outboundRpcQueue.pollFirst();
                getLogger().info("Delivered queued RPC; remaining queue=" + outboundRpcQueue.size());
            } catch (IllegalArgumentException iae) {
                getLogger().warning("Queued RPC send failed due to unregistered channel during drain: " + iae.getMessage());
                // give up this tick; will retry next scheduled run
                break;
            } catch (Exception e) {
                getLogger().warning("Queued RPC send failed during drain: " + e.getMessage());
                // give up this tick
                break;
            }
        }
    }
 }
