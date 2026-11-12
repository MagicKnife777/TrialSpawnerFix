package magicknife.trialspawnerfix;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class AuditFiles implements AuditJournal.Sink {
    private final Path directory;
    private final Path realRoot;
    private final long fileLimit;
    private final long totalLimit;
    private final Gson gson = new Gson();
    private OutputStream output;
    private Path current;
    private LocalDate day;
    private long bytes;

    AuditFiles(Path dataDirectory) throws IOException {
        this(dataDirectory, 10 * 1024 * 1024, 100 * 1024 * 1024);
    }

    AuditFiles(Path dataDirectory, long fileLimit, long totalLimit) throws IOException {
        Path root = dataDirectory.toAbsolutePath().normalize();
        directory = root.resolve("audit");
        if (!directory.startsWith(root) || Files.isSymbolicLink(directory)) {
            throw new IOException("审计目录边界无效：" + directory);
        }
        Files.createDirectories(directory);
        realRoot = root.toRealPath();
        if (!directory.toRealPath().startsWith(realRoot)) {
            throw new IOException("审计目录解析后超出插件目录：" + directory);
        }
        this.fileLimit = fileLimit;
        this.totalLimit = totalLimit;
        try {
            rotate();
        } catch (IOException exception) {
            try {
                close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    @Override
    public void write(AuditRecord record) throws IOException {
        byte[] line = (gson.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > fileLimit) {
            throw new IOException("单条审计记录超过文件容量");
        }
        if (!today().equals(day) || bytes + line.length > fileLimit) {
            rotate();
        }
        cleanup(line.length);
        output.write(line);
        output.flush();
        bytes += line.length;
    }

    private void rotate() throws IOException {
        checkDirectory();
        if (output != null) {
            output.close();
        }
        day = today();
        current = directory.resolve("trialspawnerfix-" + day + "-" + UUID.randomUUID() + ".jsonl");
        output = Files.newOutputStream(current, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        bytes = 0;
        cleanup(0);
    }

    private void cleanup(long incomingBytes) throws IOException {
        checkDirectory();
        List<Path> files;
        try (var entries = Files.list(directory)) {
            files = entries.filter(path -> path.getFileName().toString().startsWith("trialspawnerfix-")
                    && path.getFileName().toString().endsWith(".jsonl")).toList();
        }
        for (Path file : files) {
            if (!file.toAbsolutePath().normalize().getParent().equals(directory)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("审计清理目标不是目录内普通文件：" + file);
            }
        }
        // 先收集时间，排序时不把文件异常转换成静默默认值。
        var dated = new java.util.ArrayList<FileInfo>();
        for (Path file : files) {
            dated.add(new FileInfo(file, Files.getLastModifiedTime(file).toInstant(), Files.size(file)));
        }
        dated.sort(Comparator.comparing(FileInfo::modified));
        long total = incomingBytes;
        for (FileInfo file : dated) {
            total += file.size();
        }
        Instant cutoff = Instant.now().minus(14, ChronoUnit.DAYS);
        for (FileInfo file : dated) {
            if (!file.path().equals(current) && (file.modified().isBefore(cutoff) || total > totalLimit)) {
                Files.delete(file.path());
                total -= file.size();
            }
        }
        if (total > totalLimit) {
            throw new IOException("审计总容量不足");
        }
    }

    private record FileInfo(Path path, Instant modified, long size) {
    }

    private void checkDirectory() throws IOException {
        if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(realRoot)) {
            throw new IOException("审计目录已改变或超出插件目录：" + directory);
        }
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    @Override
    public void close() throws IOException {
        if (output != null) {
            output.close();
        }
    }
}
