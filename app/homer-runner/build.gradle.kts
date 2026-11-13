import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer

plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.licenseReport)
    alias(libs.plugins.dependencyCheck)
}

dependencies {
    // The protocol DTOs, TarStreams and ProcessGroupSupport come from :app. Logback
    // (its logging backend) is excluded so slf4j-simple stays the sole SLF4J
    // binding, as in :agentdebug.
    implementation(project(":app")) {
        exclude(group = "ch.qos.logback")
    }
    implementation(libs.clikt)
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.slf4jSimple)
    implementation(libs.bundles.ktorClient)

    testImplementation(libs.bundles.testing)
    // Tests assert on log events through logback's ListAppender, so the test runtime needs a
    // real backend; the runner itself keeps slf4j-simple as its only binding.
    testImplementation(libs.logbackClassic)
}

configurations.named("testRuntimeClasspath") {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

// The release this runner binary is built from: `-PhomerVersion`, else the HOMER_VERSION
// environment the release build already passes for the images (ADR-0093), else `dev`. This is
// the single source for the version the runner reports, the shadow jar's name and the generated
// resource below — no second literal. A value that could not safely be reported to Homer or put
// in a file name fails the build instead of reaching either.
val runnerVersionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
val runnerVersion: String =
    providers
        .gradleProperty("homerVersion")
        .orElse(providers.environmentVariable("HOMER_VERSION"))
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: "dev"
if (!runnerVersionPattern.matches(runnerVersion)) {
    throw GradleException("homerVersion (or HOMER_VERSION) must match $runnerVersionPattern, got '$runnerVersion'")
}
version = runnerVersion

application {
    mainClass = "homer.runner.MainKt"
}

// Runtime-dependency license gate; policy and rationale: see ADR-0056.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
    renderers = arrayOf<ReportRenderer>(TextReportRenderer("THIRD-PARTY-NOTICES.txt"))
    allowedLicensesFile = rootProject.layout.projectDirectory.file("config/allowed-licenses.json")
}

tasks.check {
    dependsOn("checkLicense")
}

// The runner's dumpability hardening makes an FFM downcall to prctl; the JDK
// warns on the restricted method without this, as the runtime image does not.
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Dumpability.harden() is process-wide, so a class that calls it in the shared
    // test JVM would make every later class' same-uid /proc read fail with EPERM
    // (and could let a status read see the wrong process). One class per JVM.
    forkEvery = 1
}

tasks.shadowJar {
    archiveBaseName.set("homer-runner")
    archiveClassifier.set("all")
    archiveVersion.set(runnerVersion)
    manifest { attributes["Main-Class"] = "homer.runner.MainKt" }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from(tasks.named("generateLicenseReport")) {
        include("THIRD-PARTY-NOTICES.txt")
        into("META-INF")
    }
}

// The compiled-in version: a classpath resource the runner reads at startup, so the jar reports
// the release it was built from without any run-time configuration. The declared input rebuilds
// it when the version changes.
val generateRunnerVersion =
    tasks.register("generateRunnerVersion") {
        val stamped = runnerVersion
        val outputDir = layout.buildDirectory.dir("generated/runner-version")
        inputs.property("runnerVersion", stamped)
        outputs.dir(outputDir)
        doLast {
            val dir = outputDir.get().asFile.resolve("homer/runner")
            dir.mkdirs()
            dir.resolve("runner-version.txt").writeText(stamped)
        }
    }

sourceSets.named("main") { resources.srcDir(generateRunnerVersion) }

// The runner image ships its own few runtime dependencies on top of :app's, so it gets the same
// CVE gate as :app (ADR-0088): the NVD mirror, runtimeClasspath only, :app's suppressions.
dependencyCheck {
    failBuildOnCVSS = 7.0f
    suppressionFile = project(":app").file("dependency-check-suppression.xml").absolutePath
    scanConfigurations = listOf("runtimeClasspath")
    nvd {
        datafeedUrl = "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz"
    }
}
