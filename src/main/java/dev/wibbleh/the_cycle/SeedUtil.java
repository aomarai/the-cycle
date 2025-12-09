package dev.wibbleh.the_cycle;

import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility for selecting a world seed for a new hardcore world.
 * <p>
 * The selection strategy:
 * - If randomize is true -> return a freshly generated random long seed.
 * - Else if configuredSeed != 0 -> return the configured seed.
 * - Otherwise -> return empty to indicate "no explicit seed" (let Minecraft choose).
 */
public final class SeedUtil {
    private SeedUtil() { /* utility */ }

    /**
     * Select a seed according to configuration.
     *
     * @param randomize whether to generate a new random seed
     * @param configuredSeed configured seed value (0 means unspecified)
     * @return OptionalLong containing the chosen seed, or empty when none selected
     */
    public static OptionalLong selectSeed(boolean randomize, long configuredSeed) {
        if (randomize) {
            return OptionalLong.of(ThreadLocalRandom.current().nextLong());
        }
        if (configuredSeed != 0L) {
            return OptionalLong.of(configuredSeed);
        }
        return OptionalLong.empty();
    }
}

