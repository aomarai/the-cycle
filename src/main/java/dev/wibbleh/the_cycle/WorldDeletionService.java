package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class WorldDeletionService {
    private final JavaPlugin plugin;
    private final boolean deletePreviousWorlds;
    private final boolean deferDeleteUntilRestart;
    private final boolean asyncDelete;
    private final File pendingDeletesFile;

    public WorldDeletionService(JavaPlugin plugin, boolean deletePreviousWorlds, boolean deferDeleteUntilRestart, boolean asyncDelete) {
        this.plugin = plugin;
        this.deletePreviousWorlds = deletePreviousWorlds;
        this.deferDeleteUntilRestart = deferDeleteUntilRestart;
        this.asyncDelete = asyncDelete;
        this.pendingDeletesFile = new File(plugin.getDataFolder(), "pending_deletes.txt");
    }

    /**
     * Read the pending deletes file and schedule deletion attempts for each recorded world.
     * If the file is empty it will be removed. Deletions are performed asynchronously.
     */
    public void processPendingDeletions() {
        if (!pendingDeletesFile.exists()) return;
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(pendingDeletesFile))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.trim().isEmpty()) lines.add(l.trim());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read pending deletes: " + e.getMessage());
            return;
        }
        if (lines.isEmpty()) {
            boolean delOk = pendingDeletesFile.delete();
            if (!delOk)
                plugin.getLogger().warning("Could not delete empty pending deletes file: " + pendingDeletesFile.getAbsolutePath());
            return;
        }
        for (String w : lines) {
            final String wn = w;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean ok = deleteWorldFolder(wn);
                if (ok) plugin.getLogger().info("Deleted pending world folder: " + wn);
                else plugin.getLogger().warning("Failed to delete pending world folder: " + wn);
            });
        }
        try {
            boolean delOk = pendingDeletesFile.delete();
            if (!delOk)
                plugin.getLogger().warning("Could not delete pending deletes file after scheduling: " + pendingDeletesFile.getAbsolutePath());
        } catch (Exception ignored) {
        }
    }

    /**
     * Schedule deletion of a world folder by name.
     * Behavior respects the service configuration:
     * - If deletes are disabled this is a no-op.
     * - If deferDeleteUntilRestart is true the world name is recorded for later.
     * - Else deletion happens synchronously or asynchronously depending on asyncDelete.
     *
     * @param worldName name of the world folder to delete
     */
    public void scheduleDeleteWorldFolder(String worldName) {
        if (!deletePreviousWorlds) return;
        if (worldName == null || worldName.trim().isEmpty()) return;
        if (deferDeleteUntilRestart) {
            recordPendingDelete(worldName);
            plugin.getLogger().info("Deferred deletion of world '" + worldName + "' until next server start.");
            return;
        }
        if (asyncDelete) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean ok = deleteWorldFolder(worldName);
                if (ok) plugin.getLogger().info("Asynchronously deleted world folder: " + worldName);
                else {
                    plugin.getLogger().warning("Asynchronous deletion failed for world: " + worldName + "; recording for restart.");
                    recordPendingDelete(worldName);
                }
            });
        } else {
            boolean ok = deleteWorldFolder(worldName);
            if (ok) plugin.getLogger().info("Deleted world folder: " + worldName);
            else {
                plugin.getLogger().warning("Deletion failed for world: " + worldName + ". Recording for deletion on restart.");
                recordPendingDelete(worldName);
            }
        }
    }

    /**
     * Append a world name to the pending deletes file unless it's already present.
     * This method is synchronized to avoid races when multiple threads attempt to record.
     *
     * @param worldName world folder name to record
     */
    private synchronized void recordPendingDelete(String worldName) {
        File df = plugin.getDataFolder();
        if (!df.exists()) {
            boolean created = df.mkdirs();
            if (!created) plugin.getLogger().warning("Failed to create data folder: " + df.getAbsolutePath());
        }
        try {
            List<String> existing = new ArrayList<>();
            if (pendingDeletesFile.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(pendingDeletesFile))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (!l.trim().isEmpty()) existing.add(l.trim());
                    }
                }
            }
            if (!existing.contains(worldName)) {
                try (BufferedWriter w = new BufferedWriter(new FileWriter(pendingDeletesFile, true))) {
                    w.write(worldName);
                    w.newLine();
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to record pending delete for " + worldName + ": " + e.getMessage());
        }
    }

    /**
     * Delete the world folder on disk. Returns true if deletion succeeded or folder did not exist.
     *
     * @param worldName name of the world folder to delete
     * @return true when deletion was successful or folder absent; false on error
     */
    private boolean deleteWorldFolder(String worldName) {
        try {
            File worldRoot = Bukkit.getWorldContainer();
            File worldFolder = new File(worldRoot, worldName);
            if (!worldFolder.exists()) return true;
            return deleteRecursively(worldFolder);
        } catch (Exception e) {
            plugin.getLogger().warning("deleteWorldFolder failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Recursively delete a directory and its contents. Attempts to set files writable when delete fails.
     *
     * @param f directory or file to delete
     * @return true on success, false on failure
     */
    private boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) return true;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) {
                    if (!deleteRecursively(c)) return false;
                } else {
                    if (!c.delete()) {
                        try {
                            boolean _w = c.setWritable(true);
                            if (!_w) plugin.getLogger().fine("Couldn't set writable: " + c.getAbsolutePath());
                        } catch (Exception ignored) {
                        }
                        if (!c.delete()) return false;
                    }
                }
            }
        }
        if (!f.delete()) {
            try {
                boolean _w = f.setWritable(true);
                if (!_w) plugin.getLogger().fine("Couldn't set writable: " + f.getAbsolutePath());
            } catch (Exception ignored) {
            }
            return f.delete();
        }
        return true;
    }
}
