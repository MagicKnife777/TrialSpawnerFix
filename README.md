# TrialSpawnerFix

用于清除试炼刷怪笼异常长冷却的 Lophine 插件，可用于处理服务器游戏时间回滚后留下的巨额冷却。

区块加载时，插件会检查其中的试炼刷怪笼。如果冷却结束时间大于 0，且减去当前世界游戏时间后的剩余冷却超过 `36000` 刻（按 20 TPS 计算为 30 分钟），则通过区域调度器重新取得方块状态，将冷却结束时间设为 `0` 并保存。

## 环境与构建

- JDK 25。
- Gradle 9.8.0，使用仓库自带的 Wrapper。
- 编译依赖固定为 Lophine API `26.3.build.961-stable`，由服务器提供，不打包进插件。

Windows PowerShell：

```powershell
.\gradlew.bat clean build --console=plain
```

Linux / macOS：

```sh
sh ./gradlew clean build --console=plain
```

产物为 `build/libs/TrialSpawnerFix-1.0.0.jar`。插件版本在 `build.gradle.kts`
中维护，构建时写入 `plugin.yml`。首次构建需要网络下载 Gradle 和依赖；
缓存完整后可以添加 `--offline`。普通构建不需要服务端 JAR。

## 本地测试服

项目使用 `xyz.jpenilla.run-paper` `3.1.0` 提供 `runServer` 任务，启动指定的本地 Lophine 服务端并自动构建、加载当前插件。

### 准备服务端

从 [Lophine 官方 Release 26.3-dab0a00](https://github.com/LophineLabs/Lophine/releases/tag/26.3-dab0a00) 下载 `lophine-26.3-morninggloryclip.jar`，重命名并放到 `run/server/lophine.jar`。该版本为 Lophine 26.3 build 961 stable，与当前 API 编译依赖对应。

### 启动

Windows PowerShell：

```powershell
.\gradlew.bat runServer --console=plain
```

Linux / macOS：

```sh
sh ./gradlew runServer --console=plain
```

也可通过 `lophineServerJar` 指定其他位置的 JAR：

```powershell
.\gradlew.bat runServer --console=plain "-PlophineServerJar=E:/Minecraft Servers/lophine.jar"
```

```sh
sh ./gradlew runServer --console=plain -PlophineServerJar=/path/to/lophine.jar
```

参数支持绝对路径和相对项目根目录的路径。

任务使用 Java 25 toolchain，最大堆内存为 `2G`。测试服工作目录为 `run/lophine/`，世界、配置、日志和首次启动下载的依赖均保存在该目录下。

首次执行 Gradle 和首次启动服务端可能需要联网下载依赖。服务端首次运行会生成 `run/lophine/eula.txt` 并提示 EULA；项目不会自动接受 EULA。

## 安装与数据

将 JAR 放入目标 Lophine 26.3 服务器的 `plugins/` 目录，然后重启服务器。插件使用区域调度器，并在描述文件中声明 `folia-supported: true`；

不同服务器实现的兼容性需要实际验证。

插件启用时会生成 `plugins/TrialSpawnerFix/config.yml`。通过 `runServer` 启动时，对应路径为 `run/lophine/plugins/TrialSpawnerFix/config.yml`。

其中 `last-shutdown-gametime` 是插件自动维护的状态字段，初始值 `-1` 表示无记录。正常停服时，插件会记录首个世界的游戏时间。当前修复逻辑不读取此字段，也不计算或扣减重启前后的时间差；它直接使用区块所在世界的当前游戏时间判断剩余冷却。该字段目前保留备用，无需手动修改。

## 目录

```text
TrialSpawnerFix/
├── src/main/java/magicknife/trialspawnerfix/
│   ├── TrialSpawnerFixPlugin.java  # 生命周期、监听器注册和状态保存
│   └── SpawnerListener.java        # 区块监听和冷却修正
├── src/main/resources/
│   ├── plugin.yml                 # 插件描述
│   └── config.yml                 # 默认状态
├── gradle/wrapper/                # 固定 Gradle 版本
├── run/                          # 本地服务端 JAR 和测试服数据
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```
