import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

// Dev build: `-PdevBuild=true` produces a separate plugin (distinct id, name, and zip) so it can be
// installed alongside the published one and toggled on/off in Settings > Plugins without uninstalling.
// Keep only ONE of the two enabled at a time — both enabled would double every contribution.
val devBuild = (providers.gradleProperty("devBuild").orNull ?: "false").toBoolean()
version = providers.gradleProperty(if (devBuild) "pluginVersionDev" else "pluginVersion").get()

group = providers.gradleProperty(if (devBuild) "pluginGroupDev" else "pluginGroup").get()

// Secrets for signing/publishing. Loaded from a gitignored `secret.properties` at the project root,
// with a fallback to environment variables. Never commit real values — see secret.properties.example.
val secretProps = Properties()
val secretPropsFile = rootProject.file("secret.properties")
if (secretPropsFile.exists()) secretPropsFile.inputStream().use { secretProps.load(it) }

fun secret(key: String): String? {
    val fromFile: String? = secretProps.getProperty(key)
    if (!fromFile.isNullOrBlank()) return fromFile
    val fromEnv: String? = System.getenv(key)
    return if (!fromEnv.isNullOrBlank()) fromEnv else null
}
fun secretFile(key: String) = secret(key)?.let { layout.projectDirectory.file(it) }

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        // CFML plugin (Marketplace id "CFML Support") — provides the CFML language + PSI we build on.
        plugin("CFML Support", providers.gradleProperty("cfmlPluginVersion").get())
        // NOTE: Database Tools is intentionally NOT a compile/test dependency — it drags the Java plugin
        // into the test sandbox and destabilizes platform tests. SELECT * expansion accesses its API via
        // reflection (see CfmlDbSchema), gated by the optional <depends> in plugin.xml.

        pluginVerifier()
        zipSigner()

        testFramework(TestFrameworkType.Platform)
    }

    // Pure-logic extractor tests (JUnit 5).
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // In-platform completion test uses BasePlatformTestCase (JUnit 4 / vintage engine).
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        id = if (devBuild) "it.emanuelemelini.cfmlextracompletion.dev" else "it.emanuelemelini.cfmlextracompletion"
        name = if (devBuild) "Cfml Extra Completion (DEV)" else "Cfml Extra Completion"
        version = project.version.toString()
        changeNotes = providers.gradleProperty("pluginChangeNotes").orElse("Initial release.")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    // Signing (recommended by JetBrains). Reads from secret.properties / env (see secret.properties.example):
    //   certificateChainFile, privateKeyFile  — paths to the .crt / .pem files
    //   PRIVATE_KEY_PASSWORD                   — passphrase for the private key
    // Used by the `signPlugin` task (and by `publishPlugin`, which signs first).
    signing {
        certificateChainFile.set(secretFile("certificateChainFile"))
        privateKeyFile.set(secretFile("privateKeyFile"))
        password.set(secret("PRIVATE_KEY_PASSWORD"))
    }

    // Publishing to JetBrains Marketplace via the `publishPlugin` task.
    //   PUBLISH_TOKEN — a permanent Marketplace token (Profile ▸ My Tokens).
    // Pre-release builds can target another channel, e.g. -PpluginChannel=eap.
    publishing {
        token.set(secret("PUBLISH_TOKEN"))
        channels = providers.gradleProperty("pluginChannel").map { listOf(it) }.orElse(listOf("default"))
    }

    pluginVerification {
        ides {
            // Compile against the OLDEST supported build (platformVersion) but verify across the whole
            // supported range, including the newest 2026.2.x users actually run.
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion").get())
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersionLatest").get())
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The 2026.2 platform is compiled with a newer Kotlin than our compiler; we only touch stable
        // Java-style APIs, so bypass the metadata version gate rather than chase the exact compiler build.
        freeCompilerArgs.addAll("-Xskip-metadata-version-check", "-Xskip-prerelease-check")
    }
}

tasks {
    wrapper {
        gradleVersion = "9.6.1"
    }
    // verifyPluginSignature reads signPlugin's output; Gradle 9 requires the dependency to be explicit.
    named("verifyPluginSignature") {
        dependsOn("signPlugin")
    }
}
