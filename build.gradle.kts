plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "magicknife"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        name = "lophine"
        url = uri("https://repo.bacteriawa.com/repository/maven-public/")
    }
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("fun.bm.lophine:lophine-api:26.3.build.961-stable")
    testImplementation("fun.bm.lophine:lophine-api:26.3.build.961-stable")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val verifyCoreTests = tasks.register("verifyCoreTests") {
    dependsOn(tasks.testClasses)
    doLast {
        if (sourceSets.test.get().output.classesDirs.asFileTree.matching { include("**/*Test.class") }.isEmpty) {
            throw GradleException("未发现核心测试，不能以 NO-SOURCE 通过验证。")
        }
    }
}

tasks.test {
    dependsOn(verifyCoreTests)
    useJUnitPlatform()
    failOnNoDiscoveredTests.set(true)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val lophineServerJar = layout.projectDirectory.file(
    providers.gradleProperty("lophineServerJar").orElse("run/server/lophine.jar")
)

tasks.runServer {
    minecraftVersion("26.3")
    serverJar(lophineServerJar)
    runDirectory.set(layout.projectDirectory.dir("run/lophine"))
    displayName.set("Lophine")
    description = "启动本地 Lophine 服务端并加载当前插件。"
    maxHeapSize = "2G"

    doFirst {
        val serverFile = lophineServerJar.get().asFile
        if (!serverFile.isFile) {
            throw GradleException(
                "未找到 Lophine 服务端 JAR：$serverFile。" +
                    "请放置到 run/server/lophine.jar，或使用 -PlophineServerJar=路径 指定。"
            )
        }
    }
}
