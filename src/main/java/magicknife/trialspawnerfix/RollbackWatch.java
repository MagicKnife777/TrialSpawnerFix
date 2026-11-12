package magicknife.trialspawnerfix;

final class RollbackWatch {
    private Long previous;
    private Long nextAllowed;
    private boolean pending;

    boolean observe(long gameTime) {
        boolean decreased = previous != null && gameTime < previous;
        previous = gameTime;
        pending |= decreased;
        return decreased;
    }

    boolean due(long now) {
        if (!pending || (nextAllowed != null && now < nextAllowed)) {
            return false;
        }
        pending = false;
        nextAllowed = now + 30_000_000_000L;
        return true;
    }
}
