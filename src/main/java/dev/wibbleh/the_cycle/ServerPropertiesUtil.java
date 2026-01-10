package dev.wibbleh.the_cycle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * Utility for updating Minecraft server.properties file settings.
 * <p>
 * This utility provides methods to update specific properties
 * in the server.properties file, preserving comments and formatting where possible.
 */
public final class ServerPropertiesUtil {
    private static final Logger LOG = Logger.getLogger("HardcoreCycle");
    
    private ServerPropertiesUtil() { /* utility */ }

    /**
     * Update the level-name property in server.properties to the specified world name.
     * <p>
     * This method:
     * 1. Reads the current server.properties file
     * 2. Updates the level-name property
     * 3. Creates a backup of the original file
     * 4. Writes the updated properties back to the file
     * <p>
     * The method preserves all other properties and their values. If the file doesn't exist,
     * it will be created with minimal default properties.
     *
     * @param serverPropertiesPath path to the server.properties file
     * @param worldName the new world name to set for level-name
     * @return true if the update was successful, false otherwise
     */
    public static boolean updateLevelName(Path serverPropertiesPath, String worldName) {
        return updateLevelNameAndSeed(serverPropertiesPath, worldName, null);
    }

    /**
     * Update the level-name and optionally level-seed properties in server.properties.
     * <p>
     * This method:
     * 1. Reads the current server.properties file
     * 2. Updates the level-name and level-seed properties
     * 3. Creates a backup of the original file
     * 4. Writes the updated properties back to the file
     * <p>
     * The method preserves all other properties and their values. If the file doesn't exist,
     * it will be created with minimal default properties.
     *
     * @param serverPropertiesPath path to the server.properties file
     * @param worldName the new world name to set for level-name
     * @param seed the seed to set for level-seed (null to leave unchanged, empty string to clear)
     * @return true if the update was successful, false otherwise
     */
    public static boolean updateLevelNameAndSeed(Path serverPropertiesPath, String worldName, String seed) {
        if (serverPropertiesPath == null || worldName == null || worldName.trim().isEmpty()) {
            LOG.warning("Invalid parameters for updateLevelNameAndSeed: path=" + serverPropertiesPath + ", worldName=" + worldName);
            return false;
        }

        File propertiesFile = serverPropertiesPath.toFile();
        
        try {
            // If file doesn't exist, create it with the level-name property
            if (!propertiesFile.exists()) {
                LOG.info("server.properties does not exist; creating with level-name=" + worldName);
                try (var writer = Files.newBufferedWriter(serverPropertiesPath, StandardCharsets.UTF_8)) {
                    writer.write("# Minecraft server properties\n");
                    writer.write("# Updated by HardcoreCycle plugin\n");
                    writer.write("level-name=" + worldName + "\n");
                    if (seed != null) {
                        writer.write("level-seed=" + seed + "\n");
                    }
                }
                return true;
            }

            // Create a backup before modifying
            Path backupPath = serverPropertiesPath.resolveSibling("server.properties.backup");
            Files.copy(serverPropertiesPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            LOG.fine("Created backup at: " + backupPath);

            // Read file line by line, updating level-name and level-seed if found
            var lines = new ArrayList<String>();
            boolean levelNameFound = false;
            boolean levelSeedFound = false;
            
            try (var reader = Files.newBufferedReader(serverPropertiesPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    
                    // Check if this line contains the level-name or level-seed property (not a comment)
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        int eqIdx = trimmed.indexOf('=');
                        String key = trimmed.substring(0, eqIdx).trim();
                        
                        if (!levelNameFound && "level-name".equals(key)) {
                            lines.add("level-name=" + worldName);
                            levelNameFound = true;
                        } else if (seed != null && !levelSeedFound && "level-seed".equals(key)) {
                            lines.add("level-seed=" + seed);
                            levelSeedFound = true;
                        } else {
                            lines.add(line);
                        }
                    } else {
                        lines.add(line);
                    }
                }
            }
            
            // If level-name wasn't found, add it at the end
            if (!levelNameFound) {
                lines.add("level-name=" + worldName);
                LOG.info("Added level-name property to server.properties");
            }
            
            // If level-seed update was requested and not found, add it
            if (seed != null && !levelSeedFound) {
                lines.add("level-seed=" + seed);
                LOG.info("Added level-seed property to server.properties");
            }
            
            // Write the updated lines back to the file
            Files.write(serverPropertiesPath, lines, StandardCharsets.UTF_8);
            LOG.info("Updated server.properties: level-name=" + worldName + (seed != null ? ", level-seed=" + seed : ""));
            return true;
            
        } catch (IOException e) {
            LOG.warning("Failed to update server.properties: " + e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.warning("Unexpected error updating server.properties: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the current level-name value from server.properties.
     * <p>
     * This is a read-only operation that doesn't modify the file.
     *
     * @param serverPropertiesPath path to the server.properties file
     * @return the current level-name value, or null if not found or on error
     */
    public static String getCurrentLevelName(Path serverPropertiesPath) {
        if (serverPropertiesPath == null) {
            return null;
        }

        File propertiesFile = serverPropertiesPath.toFile();
        if (!propertiesFile.exists()) {
            LOG.fine("server.properties does not exist at: " + serverPropertiesPath);
            return null;
        }

        try (var reader = Files.newBufferedReader(serverPropertiesPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    int eqIdx = trimmed.indexOf('=');
                    String key = trimmed.substring(0, eqIdx).trim();
                    if ("level-name".equals(key)) {
                        String value = trimmed.substring(eqIdx + 1).trim();
                        return value.isEmpty() ? null : value;
                    }
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to read server.properties: " + e.getMessage());
            return null;
        }

        return null;
    }
}
