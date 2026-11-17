import com.android.build.api.variant.BuildConfigField
import model.DistributionDimension
import model.NetworkDimension
import java.util.Properties

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("secant.android-build-conventions")
    id("wtf.emulator.gradle")
    id("secant.emulator-wtf-conventions")
    id("secant.jacoco-conventions")
}

android {
    namespace = "co.electriccoin.zcash.ui"

    defaultConfig {
        testInstrumentationRunner = "co.electriccoin.zcash.test.ZcashUiTestRunner"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.androidx.compose.compiler.get().versionConstraint.displayName
    }

    sourceSets {
        getByName("main").apply {
            res.setSrcDirs(
                setOf(
                    // This is a special case as these texts are not translated, they are replaced in build time via
                    // app/build.gradle.kts instead
                    "src/main/res/ui/non_translatable",

                    "src/main/res/ui/about",
                    "src/main/res/ui/account_list",
                    "src/main/res/ui/address_book",
                    "src/main/res/ui/add_contact",
                    "src/main/res/ui/advanced_settings",
                    "src/main/res/ui/authentication",
                    "src/main/res/ui/balances",
                    "src/main/res/ui/common",
                    "src/main/res/ui/contact",
                    "src/main/res/ui/connect_keystone",
                    "src/main/res/ui/crash_reporting_opt_in",
                    "src/main/res/ui/delete_wallet",
                    "src/main/res/ui/export_data",
                    "src/main/res/ui/error",
                    "src/main/res/ui/home",
                    "src/main/res/ui/insufficient_funds",
                    "src/main/res/ui/choose_server",
                    "src/main/res/ui/integrations",
                    "src/main/res/ui/onboarding",
                    "src/main/res/ui/pay",
                    "src/main/res/ui/payment_request",
                    "src/main/res/ui/qr_code",
                    "src/main/res/ui/request",
                    "src/main/res/ui/receive",
                    "src/main/res/ui/review_keystone_transaction",
                    "src/main/res/ui/restore",
                    "src/main/res/ui/restore_success",
                    "src/main/res/ui/scan",
                    "src/main/res/ui/scan_keystone",
                    "src/main/res/ui/security_warning",
                    "src/main/res/ui/seed_recovery",
                    "src/main/res/ui/select_keystone_account",
                    "src/main/res/ui/send",
                    "src/main/res/ui/send_confirmation",
                    "src/main/res/ui/settings",
                    "src/main/res/ui/sign_keystone_transaction",
                    "src/main/res/ui/swap",
                    "src/main/res/ui/transaction_detail",
                    "src/main/res/ui/transaction_filters",
                    "src/main/res/ui/transaction_history",
                    "src/main/res/ui/transaction_note",
                    "src/main/res/ui/tax_export",
                    "src/main/res/ui/tex_unsupported",
                    "src/main/res/ui/feedback",
                    "src/main/res/ui/update",
                    "src/main/res/ui/update_contact",
                    "src/main/res/ui/wallet_address",
                    "src/main/res/ui/warning",
                    "src/main/res/ui/whats_new",
                    "src/main/res/ui/exchange_rate",
                    "src/main/res/ui/tor",
                    // F3 (fix-all 2.13.0): the TEX checker and its automatic recovery.
                    "src/main/res/ui/tex",
                    "src/main/res/ui/invite",
                )
            )
        }
    }

    buildTypes {
        // AI-KEY-D (W-53): the debug-only switch for the AI server's address and service name, for a local check of
        // the sign-in before any deploy (-PaiAuthBaseUrl=http://127.0.0.1:<port> -PaiAuthAudience=<name>). Empty by
        // default. Only the debug type has these fields, and only ui-lib/src/debug reads them (AiAuthEndpoint).
        getByName("debug") {
            val aiAuthValue = { name: String ->
                (project.findProperty(name) as String?).orEmpty().filter { it.isLetterOrDigit() || it in ":/.-_" }
            }
            buildConfigField("String", "AI_AUTH_BASE_URL", "\"${aiAuthValue("aiAuthBaseUrl")}\"")
            buildConfigField("String", "AI_AUTH_AUDIENCE", "\"${aiAuthValue("aiAuthAudience")}\"")
        }
    }

    flavorDimensions += listOf(NetworkDimension.DIMENSION_NAME, DistributionDimension.DIMENSION_NAME)

    productFlavors {
        create(NetworkDimension.TESTNET.value) {
            dimension = NetworkDimension.DIMENSION_NAME
            // Testnet chat overlay: ZMSGConstants.PLATFORM_FEE_ADDRESS and ZchatTeamConstants.ADDRESS
            // were mainnet-hardcoded, so every chat ZIP-321 (which always appends the platform-fee
            // output) failed testnet address validation and chat was dead on this flavor.
            //
            // PLACEHOLDERS — these are the Zcash SDK's PUBLIC test-fixture unified addresses
            // (cash.z.ecc.android.sdk.fixture.WalletFixture Alice / Ben: publicly-known seeds,
            // testnet TAZ only, canonical so they PASS the SDK's testnet address validation).
            // TODO(owner): replace with the real ZCHAT testnet platform-fee / team UAs once
            // provisioned. Mainnet below is byte-identical to the previous hardcoded constants.
            buildConfigField(
                "String",
                "ZCHAT_PLATFORM_FEE_ADDRESS",
                "\"utest16zd8zfx6n6few7mjsjpn6qtn8tlg6law7qnq33257855mdqekk7vru8lettx3vud4mh99elgl" +
                    "ddltmfjkduar69h7vy08h3xdq6zuls9pqq7quyuehjqwtthc3hfd8gshhw42dfr96e\""
            )
            buildConfigField(
                "String",
                "ZCHAT_DONATION_ADDRESS",
                "\"utest16zd8zfx6n6few7mjsjpn6qtn8tlg6law7qnq33257855mdqekk7vru8lettx3vud4mh99elgl" +
                    "ddltmfjkduar69h7vy08h3xdq6zuls9pqq7quyuehjqwtthc3hfd8gshhw42dfr96e\""
            )
            buildConfigField(
                "String",
                "ZCHAT_TEAM_ADDRESS",
                "\"utest1vergg5jkp4xy8sqfasw6s5zkdpnxvfxlxh35uuc3me7dp596y2r05t6dv9htwe3pf8ksrfr8k" +
                    "sca2lskzjanqtl8uqp5vln3zyy246ejtx86vqftp73j7jg9099jxafyjhfm6u956j3\""
            )
            // ZC-1460: the ZChat Team's relay (Nostr) public key, 64 hex characters, so a newcomer can reach the Team free in
            // Open. Empty means off (the Team contact is address-only, as before). A public key only, never a secret; the
            // value comes from the owner together with who answers it.
            buildConfigField("String", "ZCHAT_TEAM_NOSTR_PUBKEY", "\"\"")
        }

        create(NetworkDimension.MAINNET.value) {
            dimension = NetworkDimension.DIMENSION_NAME
            // Production values. Since 2026-09-09 the platform fee is collected at the donation address
            // published on zsend.xyz; the Support ZCHAT button in More sends to that same address.
            buildConfigField(
                "String",
                "ZCHAT_PLATFORM_FEE_ADDRESS",
                "\"u1xcdd38s00czn0vn0nrpe2cwa8cutxw8y8m0lzgg627qap777zlzgsww7e2aqryrkuy2dvq4ug3m4" +
                        "2a8xhe623a29pyrnguk5s3hcdfag39e05uyy3z6lgvxpqtufsp2er9yxyrc2np8p3wywk7quv5cgrq" +
                        "3t5qejeupm0re5nu6cm7al\""
            )
            buildConfigField(
                "String",
                "ZCHAT_DONATION_ADDRESS",
                "\"u1xcdd38s00czn0vn0nrpe2cwa8cutxw8y8m0lzgg627qap777zlzgsww7e2aqryrkuy2dvq4ug3m4" +
                        "2a8xhe623a29pyrnguk5s3hcdfag39e05uyy3z6lgvxpqtufsp2er9yxyrc2np8p3wywk7quv5cgrq" +
                        "3t5qejeupm0re5nu6cm7al\""
            )
            buildConfigField(
                "String",
                "ZCHAT_TEAM_ADDRESS",
                "\"u12lepmf5w67kg3862d3zptqxd2wdv4zmsvqm2glcda4j0crr4uth4jd0zma8298cmfaz7zm5zn2uz" +
                        "la5250plpfsn6suy636sed07q32z4l9v55c433g0egm32d4e09zd562nrfs98kvlqm732cwmhvhzjv" +
                        "52td9v2et2587unckmdd6t\""
            )
            // ZC-1460: see the testnet flavor. Empty means off until the owner supplies the Team's key.
            buildConfigField("String", "ZCHAT_TEAM_NOSTR_PUBKEY", "\"\"")
        }

        create(DistributionDimension.STORE.value) {
            dimension = DistributionDimension.DIMENSION_NAME
            // Google Play's Device & Network Abuse policy forbids an app updating itself by any
            // route other than Play. The in-app APK updater is therefore compiled OUT of every
            // store build, and REQUEST_INSTALL_PACKAGES is declared only in the foss manifest.
            buildConfigField("boolean", "IS_SELF_UPDATE_ENABLED", "false")
        }

        create(DistributionDimension.FOSS.value) {
            dimension = DistributionDimension.DIMENSION_NAME
            // Sideloaded builds are distributed as a signed APK from GitHub, so they keep the
            // in-app updater: it is the only way a sideloading user learns about a new release.
            buildConfigField("boolean", "IS_SELF_UPDATE_ENABLED", "true")
        }
    }
}

// ZC-1268 (W-112): ZChat's own NEAR Intents account that receives the swap app fee. Read from the untracked
// local.properties (key ZCHAT_SWAP_FEE_ACCOUNT) or the environment variable of the same name; never written into
// source. ZC-1367: the value is validated whole, never rewritten: a 64-hex implicit account or a dotted NEAR name
// (lowercase letters and digits, single _ or - inside a part, 2 to 64 characters); anything else fails the build.
// Empty means the app sends no app fee (SwapFeeAccount); a release task refuses to build without it.
val zchatSwapFeeAccountRule =
    Regex("""[0-9a-f]{64}|(?:(?:[a-z0-9]+[-_])*[a-z0-9]+\.)+(?:[a-z0-9]+[-_])*[a-z0-9]+""")

val zchatSwapFeeAccount: String =
    run {
        val localFile = rootProject.file("local.properties")
        val fromLocal =
            if (localFile.isFile) {
                val properties = Properties()
                localFile.inputStream().use { stream -> properties.load(stream) }
                properties.getProperty("ZCHAT_SWAP_FEE_ACCOUNT")
            } else {
                null
            }
        (fromLocal ?: System.getenv("ZCHAT_SWAP_FEE_ACCOUNT"))
            .orEmpty()
            .trim()
    }.also { account ->
        if (account.isNotEmpty() && (account.length !in 2..64 || !zchatSwapFeeAccountRule.matches(account))) {
            throw GradleException(
                "ZCHAT_SWAP_FEE_ACCOUNT is not a valid NEAR account: use a 64-character lowercase hex account or a " +
                    "lowercase name such as zchat.near, exactly as registered"
            )
        }
    }

gradle.taskGraph.whenReady {
    val releaseTask = allTasks.any { it.project == project && it.name.contains("Release") }
    if (releaseTask && zchatSwapFeeAccount.isEmpty()) {
        throw GradleException(
            "ZCHAT_SWAP_FEE_ACCOUNT is not set: add it to local.properties or the environment for a release build"
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields.put(
            "ZCHAT_SWAP_FEE_ACCOUNT",
            BuildConfigField(
                type = "String",
                value = "\"$zchatSwapFeeAccount\"",
                comment = "ZChat's NEAR Intents account for the swap app fee (empty: no app fee is sent)"
            )
        )
        // Configure SecureScreen for protecting screens with sensitive data in runtime
        variant.buildConfigFields.put(
            "IS_SECURE_SCREEN_ENABLED",
            BuildConfigField(
                type = "boolean",
                value = project.property("IS_SECURE_SCREEN_PROTECTION_ACTIVE").toString(),
                comment = "Whether is the SecureScreen sensitive data protection enabled"
            )
        )
        variant.buildConfigFields.put(
            "ZCASH_FLEXA_KEY",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("ZCASH_FLEXA_KEY")?.toString().orEmpty()}\"",
                comment = "Publishable key of the Flexa integration"
            )
        )
        variant.buildConfigFields.put(
            "ZCASH_CMC_KEY",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("ZCASH_CMC_KEY")?.toString().orEmpty()}\"",
                comment = "Publishable key of the CMC integration"
            )
        )
        // To configure screen orientation in runtime
        variant.buildConfigFields.put(
            "IS_SCREEN_ROTATION_ENABLED",
            BuildConfigField(
                type = "boolean",
                value = project.property("IS_SCREEN_ROTATION_ENABLED").toString(),
                comment = "Whether is the screen rotation enabled, otherwise, it's locked in the portrait mode"
            )
        )
    }
}

dependencies {
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.splash)
    implementation(libs.androidx.workmanager)
    implementation(libs.androidx.browser)
    implementation(libs.bundles.androidx.camera)
    implementation(libs.bundles.androidx.compose.core)
    implementation(libs.bundles.androidx.compose.extended)
    api(libs.bundles.koin)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.immutable)
    implementation(libs.kotlinx.serializable.json)
    "storeImplementation"(libs.mlkit.scanning)
    api(libs.zcash.sdk)
    implementation(libs.zcash.sdk.incubator)
    implementation(libs.zcash.bip39)
    implementation(libs.tink)
    implementation(libs.zxing)
    implementation(libs.zxing.cpp) // 10-15x faster native QR decoding for camera scanner
    implementation(libs.androidx.security.crypto)  // For EncryptedSharedPreferences (E2E keys)
    implementation(libs.secp256k1.kmp)
    implementation(libs.secp256k1.kmp.jni.android)
    // Stream's repackaged AOSP WebRTC — voice + video peer connections, STUN/TURN, DTLS-SRTP.
    // Pulls in libjingle_peerconnection_so.so for armeabi-v7a/arm64-v8a/x86_64 (~28 MB).
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    api(libs.flexa.core)
    api(libs.flexa.spend)

    implementation(projects.buildInfoLib)
    implementation(projects.configurationApiLib)
    implementation(projects.crashAndroidLib)
    implementation(projects.preferenceApiLib)
    implementation(projects.preferenceImplAndroidLib)
    implementation(projects.spackleAndroidLib)
    api(projects.configurationImplAndroidLib)
    api(projects.sdkExtLib)
    api(projects.uiDesignLib)
    api(libs.androidx.fragment)
    api(libs.androidx.fragment.compose)
    api(libs.androidx.activity)
    api(libs.bundles.androidx.biometric)

    api(libs.keystone)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("junit:junit:4.13.2")
    // JVM JNI variant of secp256k1 for pure-JVM unit tests (the jni-android variant
    // only works on Android, not JVM test runtime).
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    // WebSocket-aware fake server for RelayClient tests.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Real org.json on the unit-test classpath. android.jar's org.json is a no-op stub under
    // isReturnDefaultValues=true, so code using JSONObject/JSONArray (e.g. the ratchet state
    // store) can't be exercised in JVM tests without this.
    testImplementation("org.json:json:20231013")

    androidTestImplementation(projects.testLib)
    androidTestImplementation(libs.bundles.androidx.test)
    androidTestImplementation(libs.androidx.compose.test.junit)
    androidTestImplementation(libs.androidx.compose.test.manifest)
    androidTestImplementation(libs.kotlin.reflect)
    androidTestImplementation(libs.kotlin.test)

    androidTestUtil(libs.androidx.test.services) {
        artifact {
            type = "apk"
        }
    }

    if (project.property("IS_USE_TEST_ORCHESTRATOR").toString().toBoolean()) {
        androidTestUtil(libs.androidx.test.services)
        androidTestUtil(libs.androidx.test.orchestrator) {
            artifact {
                type = "apk"
            }
        }
    }
}

// These tests deliberately exercise the JVM default uncaught-exception handler. Once another
// class has used runTest, coroutines-test records those intentional crashes process-wide and
// replays them in an unrelated test. Keep the real fallback path in its own worker, and make
// every ordinary unit-test task depend on it so the coverage cannot silently disappear.
afterEvaluate {
    tasks.withType<Test>().filter { it.name.endsWith("UnitTest") }.forEach { unitTests ->
        val compileDependencies = unitTests.dependsOn.toList()
        val startupIntegration = tasks.register<Test>("${unitTests.name}StartupIntegration") {
            description = "Checks startup crash handling in an isolated JVM."
            group = "verification"
            testClassesDirs = unitTests.testClassesDirs
            classpath = unitTests.classpath
            dependsOn(compileDependencies)
            javaLauncher.set(unitTests.javaLauncher)
            minHeapSize = unitTests.minHeapSize
            maxHeapSize = unitTests.maxHeapSize
            jvmArgs(unitTests.jvmArgs ?: emptyList<String>())
            systemProperties(unitTests.systemProperties)
            enableAssertions = unitTests.enableAssertions
            workingDir = unitTests.workingDir
            useJUnit()
            include("**/SynchronizerInitFailureSurfacesErrorTest.class")
            // A renamed or moved class must fail this task, never skip it as NO-SOURCE, and a cached or
            // up-to-date result must never stand in for running these checks again.
            filter {
                includeTestsMatching("*.SynchronizerInitFailureSurfacesErrorTest")
                isFailOnNoMatchingTests = true
            }
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
        unitTests.exclude("**/SynchronizerInitFailureSurfacesErrorTest.class")
        unitTests.dependsOn(startupIntegration)
    }
}
