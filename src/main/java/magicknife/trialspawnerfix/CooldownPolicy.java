package magicknife.trialspawnerfix;

import java.util.OptionalLong;

public final class CooldownPolicy {
    private CooldownPolicy() {
    }

    public enum Stage {
        INACTIVE, WAITING_FOR_PLAYERS, ACTIVE, WAITING_FOR_REWARD_EJECTION, EJECTING_REWARD, COOLDOWN
    }

    public enum Strategy {
        REPORT_ONLY("report-only"), RESTART_COOLDOWN("restart-cooldown"), EXPIRE_NOW("expire-now");

        private final String configValue;

        Strategy(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        public static Strategy parse(String value) {
            for (Strategy strategy : values()) {
                if (strategy.configValue.equals(value)) {
                    return strategy;
                }
            }
            throw new IllegalArgumentException("strategy 必须为 report-only、restart-cooldown 或 expire-now");
        }
    }

    public enum Reason {
        WORLD_DISABLED, NOT_COOLDOWN, INVALID_INPUT, EXPIRED, NORMAL, SUSPECTED, ARITHMETIC_OVERFLOW
    }

    public record Input(boolean worldEnabled, Stage stage, boolean ominous, long gameTime,
                        long cooldownEnd, long cooldownLength, long margin, Strategy strategy,
                        long configGeneration) {
    }

    public record Decision(Reason reason, OptionalLong remaining, OptionalLong newEnd) {
        public boolean suspected() {
            return reason == Reason.SUSPECTED;
        }
    }

    public static Decision evaluate(Input input) {
        if (!input.worldEnabled()) {
            return unchanged(Reason.WORLD_DISABLED);
        }
        if (input.stage() == null || input.strategy() == null || input.gameTime() < 0
                || input.cooldownLength() <= 0 || input.margin() < 0) {
            return unchanged(Reason.INVALID_INPUT);
        }
        if (input.stage() != Stage.COOLDOWN) {
            return unchanged(Reason.NOT_COOLDOWN);
        }
        if (input.cooldownEnd() <= input.gameTime()) {
            return unchanged(Reason.EXPIRED);
        }
        try {
            long remaining = Math.subtractExact(input.cooldownEnd(), input.gameTime());
            long limit = Math.addExact(input.cooldownLength(), input.margin());
            if (remaining <= limit) {
                return new Decision(Reason.NORMAL, OptionalLong.of(remaining), OptionalLong.empty());
            }
            OptionalLong newEnd = switch (input.strategy()) {
                case REPORT_ONLY -> OptionalLong.empty();
                case RESTART_COOLDOWN -> OptionalLong.of(Math.addExact(input.gameTime(), input.cooldownLength()));
                case EXPIRE_NOW -> OptionalLong.of(input.gameTime());
            };
            return new Decision(Reason.SUSPECTED, OptionalLong.of(remaining), newEnd);
        } catch (ArithmeticException exception) {
            return unchanged(Reason.ARITHMETIC_OVERFLOW);
        }
    }

    private static Decision unchanged(Reason reason) {
        return new Decision(reason, OptionalLong.empty(), OptionalLong.empty());
    }
}
