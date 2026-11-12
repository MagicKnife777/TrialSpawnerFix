package magicknife.trialspawnerfix;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandRequestTest {
    private final String world = UUID.randomUUID().toString();

    @Test
    void parsesCommandsAndMapsEachToItsDeclaredPermission() {
        String[][] commands = {{"status"}, {"inspect", world, "1", "64", "-2"}, {"scan", "loaded"},
                {"scan", "area", world, "0", "0", "32"}, {"repair", world, "1", "64", "-2", "expire-now"}, {"reload"}};
        String[] permissions = {"inspect", "inspect", "scan", "scan", "repair", "reload"};
        for (int i = 0; i < commands.length; i++) {
            assertEquals("trialspawnerfix." + permissions[i], CommandRequest.parse(commands[i]).kind().permission());
        }
        var inspect = CommandRequest.parse(commands[1]);
        assertEquals(UUID.fromString(world), inspect.world());
        assertEquals(CooldownPolicy.Strategy.REPORT_ONLY, inspect.strategy());
        assertEquals(-2, inspect.point().z());
    }

    @Test
    void rejectsInvalidUuidRangesCoordinatesAndReadonlyRepair() {
        String[][] invalid = {{"inspect", "1-1-1-1-1", "1", "64", "2"}, {"scan", "area", world, "0", "0", "33"},
                {"scan", "area", world, "0", "0", "-1"}, {"inspect", world, "2147483648", "64", "0"},
                {"repair", world, "0", "64", "0", "report-only"}, {"scan", "loaded", world, "extra"}};
        for (String[] args : invalid) {
            assertThrows(IllegalArgumentException.class, () -> CommandRequest.parse(args));
        }
        var area = new CommandRequest.Area(1_875_000, -1_875_000, 32);
        assertTrue(area.contains(1_874_968, -1_874_968));
        assertFalse(area.contains(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }
}
