package magicknife.trialspawnerfix;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CooldownPolicyTest {
    private CooldownPolicy.Input input(CooldownPolicy.Stage stage, boolean ominous, long g, long e,
                                       long length, long margin, CooldownPolicy.Strategy strategy) {
        return new CooldownPolicy.Input(true, stage, ominous, g, e, length, margin, strategy, 1);
    }

    @Test
    void preservesEveryNonCooldownStageIncludingOminousCombatAndRewards() {
        for (var stage : CooldownPolicy.Stage.values()) {
            if (stage == CooldownPolicy.Stage.COOLDOWN) {
                continue;
            }
            for (boolean ominous : new boolean[]{false, true}) {
                var result = CooldownPolicy.evaluate(input(stage, ominous, 100, 999_999, 36_000, 200,
                        CooldownPolicy.Strategy.EXPIRE_NOW));
                assertEquals(CooldownPolicy.Reason.NOT_COOLDOWN, result.reason());
                assertTrue(result.newEnd().isEmpty());
            }
        }
    }

    @Test
    void respectsExpiryActualLengthAndToleranceBoundaryForBothModes() {
        for (boolean ominous : new boolean[]{false, true}) {
            for (long remaining : new long[]{-1, 0, 72_000, 72_200, 72_201}) {
                var result = CooldownPolicy.evaluate(input(CooldownPolicy.Stage.COOLDOWN, ominous,
                        100, 100 + remaining, 72_000, 200, CooldownPolicy.Strategy.RESTART_COOLDOWN));
                assertEquals(remaining <= 0 ? CooldownPolicy.Reason.EXPIRED
                        : remaining <= 72_200 ? CooldownPolicy.Reason.NORMAL : CooldownPolicy.Reason.SUSPECTED,
                        result.reason());
                assertEquals(remaining > 72_200, result.newEnd().isPresent());
            }
        }
        assertEquals(CooldownPolicy.Reason.EXPIRED, CooldownPolicy.evaluate(input(
                CooldownPolicy.Stage.COOLDOWN, false, 100, 0, 36_000, 200,
                CooldownPolicy.Strategy.EXPIRE_NOW)).reason());
    }

    @Test
    void appliesOnlyTheSelectedRecoveryAndIsIdempotentAcrossWideInputs() {
        Random random = new Random(961);
        for (int index = 0; index < 10_000; index++) {
            long g = random.nextLong(1_000_000_000_000L);
            long length = random.nextLong(1, 1_000_000);
            for (var strategy : CooldownPolicy.Strategy.values()) {
                var original = input(CooldownPolicy.Stage.COOLDOWN, index % 2 == 0, g,
                        g + length + 201, length, 200, strategy);
                var result = CooldownPolicy.evaluate(original);
                assertTrue(result.suspected());
                if (strategy == CooldownPolicy.Strategy.REPORT_ONLY) {
                    assertTrue(result.newEnd().isEmpty());
                } else {
                    long end = result.newEnd().orElseThrow();
                    assertEquals(strategy == CooldownPolicy.Strategy.EXPIRE_NOW ? g : g + length, end);
                    assertTrue(CooldownPolicy.evaluate(input(original.stage(), original.ominous(), g, end,
                            length, 200, strategy)).newEnd().isEmpty());
                }
            }
        }
        var before = input(CooldownPolicy.Stage.COOLDOWN, false, 1000, 999_999, 100, 200,
                CooldownPolicy.Strategy.RESTART_COOLDOWN);
        long repaired = CooldownPolicy.evaluate(before).newEnd().orElseThrow();
        assertTrue(CooldownPolicy.evaluate(input(before.stage(), false, 0, repaired, 100, 200,
                before.strategy())).suspected());
    }

    @Test
    void rejectsInvalidAndOverflowingInputsWithoutWriting() {
        for (var bad : new CooldownPolicy.Input[]{
                input(null, false, 0, 100, 1, 0, CooldownPolicy.Strategy.EXPIRE_NOW),
                input(CooldownPolicy.Stage.COOLDOWN, false, -1, 100, 1, 0, CooldownPolicy.Strategy.EXPIRE_NOW),
                input(CooldownPolicy.Stage.COOLDOWN, false, 0, 100, 0, 0, CooldownPolicy.Strategy.EXPIRE_NOW),
                input(CooldownPolicy.Stage.COOLDOWN, false, 0, 100, 1, -1, CooldownPolicy.Strategy.EXPIRE_NOW),
                input(CooldownPolicy.Stage.COOLDOWN, false, 0, 100, 1, 0, null)}) {
            assertEquals(CooldownPolicy.Reason.INVALID_INPUT, CooldownPolicy.evaluate(bad).reason());
        }
        var overflow = CooldownPolicy.evaluate(input(CooldownPolicy.Stage.COOLDOWN, false, 0,
                Long.MAX_VALUE, Long.MAX_VALUE, 1, CooldownPolicy.Strategy.RESTART_COOLDOWN));
        assertEquals(CooldownPolicy.Reason.ARITHMETIC_OVERFLOW, overflow.reason());
        assertTrue(overflow.newEnd().isEmpty());
        var disabled = new CooldownPolicy.Input(false, CooldownPolicy.Stage.COOLDOWN, false,
                0, 1000, 1, 0, CooldownPolicy.Strategy.EXPIRE_NOW, 1);
        assertEquals(CooldownPolicy.Reason.WORLD_DISABLED, CooldownPolicy.evaluate(disabled).reason());
    }
}
