plugins {
    java
    jacoco
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

jacoco {
    toolVersion = "0.8.14"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "CLASS"
            includes = listOf("magicknife.trialspawnerfix.CooldownPolicy")
            limit {
                counter = "BRANCH"
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

val integrationDriver = sourceSets.create("integrationDriver")
configurations[integrationDriver.compileClasspathConfigurationName].extendsFrom(configurations.compileOnly.get())

tasks.register<Jar>("integrationDriverJar") {
    dependsOn(tasks.named(integrationDriver.classesTaskName))
    archiveBaseName.set("TrialSpawnerFixIntegrationDriver")
    archiveVersion.set("")
    from(integrationDriver.output)
}

val integrationRunner = sourceSets.create("integrationRunner")
configurations[integrationRunner.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())

tasks.register<JavaExec>("phase2IntegrationTest") {
    group = "verification"
    description = "使用已缓存的 Lophine 961 和已接受的 EULA 验证阶段 2。"
    dependsOn(tasks.jar, tasks.named("integrationDriverJar"), tasks.named(integrationRunner.classesTaskName))
    classpath = integrationRunner.runtimeClasspath
    mainClass.set("magicknife.trialspawnerfix.integration.Phase2TestRunner")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    args(layout.projectDirectory.asFile.absolutePath,
        providers.gradleProperty("phase2RuntimeDirectory").getOrElse("run/lophine"),
        providers.gradleProperty("phase2AcceptedEulaFile").getOrElse("run/lophine/eula.txt"),
        tasks.jar.get().archiveFile.get().asFile.absolutePath,
        tasks.named<Jar>("integrationDriverJar").get().archiveFile.get().asFile.absolutePath)
}

tasks.register("phase4IntegrationTest") {
    group = "verification"
    description = "验证区域检查、管理员命令与配置重载的真实服务端行为。"
    dependsOn("phase2IntegrationTest")
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
