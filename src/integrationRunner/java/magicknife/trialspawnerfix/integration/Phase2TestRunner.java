package magicknife.trialspawnerfix.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public final class Phase2TestRunner {
    private Phase2TestRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path runtime = root.resolve(args[1]).toRealPath();
        Path eula = root.resolve(args[2]).toRealPath();
        Properties acceptance = new Properties();
        try (var input = Files.newInputStream(eula)) {
            acceptance.load(input);
        }
        require("true".equals(acceptance.getProperty("eula")), "管理员尚未明确接受 EULA；测试不能启动。");
        Path server = runtime.resolve("versions/26.3/lophine-26.3.jar");
        require(Files.isRegularFile(server), "缺少已缓存的固定 Lophine 961；请先准备 runServer 环境。");
        try (var jar = new JarFile(server.toFile())) {
            require(jar.getManifest() != null && "26.3-961-dab0a00".equals(
                    jar.getManifest().getMainAttributes().getValue("Implementation-Version")),
                    "服务端不是固定 Lophine 961，不能作为阶段 2 验收平台。");
        }
        List<Path> libraries;
        try (var files = Files.walk(runtime.resolve("libraries"))) {
            libraries = files.filter(path -> path.toString().endsWith(".jar")).sorted().toList();
        }
        require(!libraries.isEmpty(), "服务端依赖缓存为空。");
        var classpath = new ArrayList<Path>();
        classpath.add(server);
        classpath.addAll(libraries);
        String joined = classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path runRoot = root.resolve("build/integration-phase-2/" + UUID.randomUUID());
        for (String strategy : List.of("report-only", "restart-cooldown", "expire-now")) {
            Path directory = runRoot.resolve(strategy);
            prepare(directory, eula, Path.of(args[3]), Path.of(args[4]), strategy);
            run(java, joined, directory);
            int assertions = verify(directory, strategy);
            System.out.println(strategy + " PASS assertions=" + assertions + " result="
                    + directory.resolve("integration-result.json"));
        }
    }

    private static void prepare(Path directory, Path eula, Path plugin, Path driver, String strategy)
            throws IOException {
        Path plugins = Files.createDirectories(directory.resolve("plugins/TrialSpawnerFix")).getParent();
        Files.copy(eula, directory.resolve("eula.txt"));
        Files.copy(plugin, plugins.resolve(plugin.getFileName()));
        Files.copy(driver, plugins.resolve(driver.getFileName()));
        Files.writeString(plugins.resolve("TrialSpawnerFix/config.yml"),
                "schema-version: 1\nstrategy: " + strategy + "\ntolerance-ticks: 200\n");
        Files.writeString(directory.resolve("server.properties"), """
                server-ip=127.0.0.1
                server-port=0
                online-mode=false
                level-seed=961
                level-type=minecraft:flat
                generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
                generate-structures=false
                view-distance=2
                simulation-distance=2
                pause-when-empty-seconds=-1
                """);
    }

    private static void run(Path java, String classpath, Path directory) throws Exception {
        // 参数列表交给 ProcessBuilder，路径中的空格不依赖 shell 引号。
        Process process = new ProcessBuilder(java.toString(), "-Xmx2G", "-XX:ActiveProcessorCount=4",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", classpath, "org.bukkit.craftbukkit.Main", "--nogui")
                .directory(directory.toFile())
                .redirectOutput(directory.resolve("stdout.log").toFile())
                .redirectError(directory.resolve("stderr.log").toFile()).start();
        try {
            require(process.waitFor(120, TimeUnit.SECONDS), "真服测试超时；日志位于 " + directory);
            require(process.exitValue() == 0, "真服退出失败；日志位于 " + directory);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                require(process.waitFor(10, TimeUnit.SECONDS), "超时服务端无法结束：" + directory);
            }
        }
    }

    private static int verify(Path directory, String strategy) throws IOException {
        Path resultPath = directory.resolve("integration-result.json");
        require(Files.isRegularFile(resultPath), "缺少结构化断言结果：" + directory);
        JsonObject result = JsonParser.parseString(Files.readString(resultPath)).getAsJsonObject();
        require(strategy.equals(result.get("strategy").getAsString())
                && result.getAsJsonArray("failed").isEmpty() && result.getAsJsonArray("passed").size() >= 31,
                "真服断言失败或数量不足：" + resultPath);
        var records = new ArrayList<JsonObject>();
        try (var files = Files.list(directory.resolve("plugins/TrialSpawnerFix/audit"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("trialspawnerfix-")
                    && path.toString().endsWith(".jsonl")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    records.add(JsonParser.parseString(line).getAsJsonObject());
                }
            }
        }
        List<JsonObject> attempts = events(records, "repair_attempt");
        List<JsonObject> results = events(records, "repair_result");
        if (strategy.equals("report-only")) {
            require(attempts.isEmpty() && results.isEmpty() && events(records, "suspected").size() >= 2,
                    "只读审计内容不符：" + directory);
        } else {
            require(attempts.size() >= 3 && attempts.size() == results.size(), "修复审计缺少尝试或结果：" + directory);
            Set<Long> pending = new HashSet<>();
            for (JsonObject attempt : attempts) {
                require(pending.add(attempt.get("eventId").getAsLong()), "审计尝试编号重复：" + directory);
            }
            for (JsonObject record : results) {
                require("repaired".equals(record.get("result").getAsString())
                        && pending.remove(record.get("attemptId").getAsLong()), "审计结果或关联不符：" + directory);
            }
            require(pending.isEmpty(), "审计尝试缺少唯一结果：" + directory);
        }
        return result.getAsJsonArray("passed").size();
    }

    private static List<JsonObject> events(List<JsonObject> records, String event) {
        return records.stream().filter(record -> event.equals(record.get("event").getAsString())).toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
