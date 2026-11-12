package magicknife.trialspawnerfix;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RollbackWatchTest {
    @Test
    void mergesRepeatedRollbacksAndRetainsOneFollowupUntilThirtySeconds() {
        var watch = new RollbackWatch();
        assertFalse(watch.observe(1000));
        assertFalse(watch.due(-1));
        assertTrue(watch.observe(100));
        assertTrue(watch.due(-1));
        assertTrue(watch.observe(10));
        assertTrue(watch.observe(1));
        assertFalse(watch.due(29_999_999_998L));
        assertTrue(watch.due(29_999_999_999L));
        assertFalse(watch.due(60_000_000_000L));
        assertFalse(watch.observe(2));
    }
}
