package dev.wibbleh.the_cycle;

import java.io.*;
import java.util.Set;
import java.util.UUID;

/**
 * Utility for persisting and loading pending move UUID sets to a simple JSON-like file.
 * This isolates file-format logic so unit tests can exercise persistence without creating
 * a full {@link Main} plugin instance.
 */
public final class PendingMovesStorage {
    private PendingMovesStorage() {}

    public static void save(File f, Set<UUID> lobby, Set<UUID> hardcore) throws IOException {
        if (f == null) throw new IllegalArgumentException("file is null");
        File df = f.getParentFile();
        if (df != null && !df.exists()) {
            boolean created = df.mkdirs();
            if (!created) throw new IOException("Failed to create directory: " + df.getAbsolutePath());
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"lobby\":[");
            boolean first = true;
            if (lobby != null) {
                for (UUID u : lobby) {
                    if (!first) sb.append(','); first = false; sb.append('"').append(u.toString()).append('"');
                }
            }
            sb.append("],\"hardcore\":[");
            first = true;
            if (hardcore != null) {
                for (UUID u : hardcore) {
                    if (!first) sb.append(','); first = false; sb.append('"').append(u.toString()).append('"');
                }
            }
            sb.append("]}");
            w.write(sb.toString());
        }
    }

    public static void load(File f, Set<UUID> lobby, Set<UUID> hardcore) throws IOException {
        if (f == null) throw new IllegalArgumentException("file is null");
        if (!f.exists()) return;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        String s = sb.toString();
        if (lobby != null) lobby.clear();
        if (hardcore != null) hardcore.clear();
        int li = s.indexOf("\"lobby\":");
        if (li >= 0) {
            int a = s.indexOf('[', li); int b = s.indexOf(']', a);
            if (a >= 0 && b >= 0) {
                String inner = s.substring(a+1, b).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        String q = part.trim().replaceAll("[\" ]", "");
                        try { if (!q.isEmpty() && lobby != null) lobby.add(UUID.fromString(q)); } catch (Exception ignored) {}
                    }
                }
            }
        }
        int hi = s.indexOf("\"hardcore\":");
        if (hi >= 0) {
            int a = s.indexOf('[', hi); int b = s.indexOf(']', a);
            if (a >= 0 && b >= 0) {
                String inner = s.substring(a+1, b).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        String q = part.trim().replaceAll("[\" ]", "");
                        try { if (!q.isEmpty() && hardcore != null) hardcore.add(UUID.fromString(q)); } catch (Exception ignored) {}
                    }
                }
            }
        }
    }
}
