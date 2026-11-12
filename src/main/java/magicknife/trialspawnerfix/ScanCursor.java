package magicknife.trialspawnerfix;

import java.util.List;

final class ScanCursor {
    private final List<CheckCoordinator.Key> keys;
    private int offset;

    ScanCursor(List<CheckCoordinator.Key> keys) {
        this.keys = List.copyOf(keys);
    }

    boolean feed(CheckCoordinator coordinator, CheckCoordinator.Source source, long scanId, int pageSize) {
        for (int i = 0; i < pageSize && offset < keys.size(); i++) {
            if (!coordinator.offer(keys.get(offset), source, scanId)) {
                return false;
            }
            offset++;
        }
        return offset == keys.size();
    }

    int remaining() {
        return keys.size() - offset;
    }
}
