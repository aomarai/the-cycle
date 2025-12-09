package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

public class SeedUtilTest {

    @Test
    public void selectSeed_randomizeTrue_returnsPresent() {
        OptionalLong s = SeedUtil.selectSeed(true, 0L);
        assertTrue(s.isPresent(), "Expected a seed when randomize=true");
    }

    @Test
    public void selectSeed_randomizeFalse_configuredNonZero_returnsConfigured() {
        OptionalLong s = SeedUtil.selectSeed(false, 12345L);
        assertTrue(s.isPresent());
        assertEquals(12345L, s.getAsLong());
    }

    @Test
    public void selectSeed_randomizeFalse_configuredZero_returnsEmpty() {
        OptionalLong s = SeedUtil.selectSeed(false, 0L);
        assertTrue(s.isEmpty());
    }
}

