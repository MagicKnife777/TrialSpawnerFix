package magicknife.trialspawnerfix;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {
    @Test
    void defaultsToReadOnlyAndIgnoresLegacyState() {
        var config = new YamlConfiguration();
        config.set("last-shutdown-gametime", 999);
        var settings = PluginSettings.parse(config, 7);
        assertEquals(CooldownPolicy.Strategy.REPORT_ONLY, settings.strategy());
        assertTrue(settings.enables(UUID.randomUUID()));
        assertEquals(7, settings.generation());
        assertEquals(999, config.getInt("last-shutdown-gametime"));
    }

    @Test
    void onlyEnablesConfiguredWorldsAndCopiesTheirIdentity() {
        UUID world = UUID.randomUUID();
        var config = new YamlConfiguration();
        config.set("enabled-worlds", List.of(world.toString()));
        var settings = PluginSettings.parse(config, 1);
        assertTrue(settings.enables(world));
        assertFalse(settings.enables(UUID.randomUUID()));
        assertThrows(UnsupportedOperationException.class, () -> settings.worlds().clear());
    }

    @Test
    void invalidFieldsFailInsteadOfSilentlyUsingDefaults() {
        Object[][] invalid = {{"strategy", "unknown"}, {"schema-version", 2},
                {"tolerance-ticks", -1}, {"tolerance-ticks", 1.5}, {"audit.queue-capacity", 1},
                {"audit.enabled", "true"}, {"enabled-worlds", List.of("1-1-1-1-1")},
                {"enabled-worlds", "world"}, {"max-candidate-buffer-per-chunk", 0},
                {"max-pending-chunks", 0}, {"max-in-flight-chunks", 262_145},
                {"dispatch-chunks-per-tick", 0}, {"candidates-per-slice", 0},
                {"slice-soft-budget-ms", 51}, {"max-loaded-scan-chunks", 0},
                {"scan-on-startup", "true"}, {"observe-time", "true"}};
        for (Object[] entry : invalid) {
            var config = new YamlConfiguration();
            config.set((String) entry[0], entry[1]);
            var error = assertThrows(IllegalArgumentException.class, () -> PluginSettings.parse(config, 1));
            assertTrue(error.getMessage().contains((String) entry[0]));
        }
    }
}
