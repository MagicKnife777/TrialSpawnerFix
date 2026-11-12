package magicknife.trialspawnerfix;

import java.util.List;
import java.util.function.Consumer;

final class ScanCursor {
    private final List<CheckCoordinator.Key> keys;
    private int offset;

    ScanCursor(List<CheckCoordinator.Key> keys) {
        this.keys = List.copyOf(keys);
    }

    boolean feed(CheckCoordinator coordinator, CheckCoordinator.Source source, long scanId, int pageSize) {
        return feed(coordinator, source, scanId, pageSize, null);
    }

    boolean feed(CheckCoordinator coordinator, CheckCoordinator.Source source, long scanId, int pageSize,
                 Consumer<Boolean> completed) {
        return feed(coordinator, source, scanId, pageSize, null, completed);
    }

    boolean feed(CheckCoordinator coordinator, CheckCoordinator.Source source, long scanId, int pageSize,
                 String actor, Consumer<Boolean> completed) {
        for (int i = 0; i < pageSize && offset < keys.size(); i++) {
            if (!coordinator.offer(keys.get(offset), source, scanId, actor, completed)) {
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
