import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

version = "0.0.27"
description = "Backport of gradient user roles & custom display names features from DiscordRN"

dependencies {
    implementation("io.github.khoben.woff2-android:typeface:0.0.2") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "io.github.khoben.woff2-android", module = "decoder")
    }
    implementation("androidx.startup:startup-runtime:1.2.0") {
        exclude(group = "org.jetbrains.kotlin")
    }
}

val woff2SourceDir = layout.projectDirectory.dir("../../third_party/woff2-android")
val woff2NativeOutputDir = layout.buildDirectory.dir("modern_user_styles_native")

fun androidSdkDir(): File {
    System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { return file(it) }
    System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { return file(it) }

    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        localProperties.readLines()
            .firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter('=')
            ?.replace("\\:", ":")
            ?.replace("\\\\", "\\")
            ?.let { return file(it) }
    }

    throw GradleException("Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties.")
}

fun androidSdkPackageVersion(sdkDir: File, packageName: String, envName: String): String {
    System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it }
    val packageDir = sdkDir.resolve(packageName)
    return packageDir.listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.name }
        ?.sortedDescending()
        ?.firstOrNull()
        ?: throw GradleException("Android SDK package '$packageName' not found in $sdkDir.")
}

fun executableName(name: String): String =
    if (System.getProperty("os.name").lowercase().contains("windows")) "$name.exe" else name

fun pythonCommand(): String {
    System.getenv("MODERN_USER_STYLES_PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
    return if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3"
}

fun runCommand(workingDir: File, command: List<String>) {
    println(command.joinToString(" "))
    val exitCode = ProcessBuilder(command)
        .directory(workingDir)
        .inheritIO()
        .start()
        .waitFor()
    if (exitCode != 0) {
        throw GradleException("Command failed with exit code $exitCode: ${command.joinToString(" ")}")
    }
}

val buildWoff2DecoderFromSource = tasks.register("buildWoff2DecoderFromSource") {
    val outputDir = woff2NativeOutputDir
    outputs.dir(outputDir)

    doLast {
        val sourceDir = woff2SourceDir.asFile
        val buildScript = sourceDir.resolve("scripts/build_ndk.py")
        if (!buildScript.exists()) {
            throw GradleException("Missing woff2-android source checkout. Run: git submodule update --init --recursive")
        }

        val sdkDir = androidSdkDir()
        val ndkVersion = androidSdkPackageVersion(sdkDir, "ndk", "MODERN_USER_STYLES_NDK_VERSION")
        val cmakeVersion = androidSdkPackageVersion(sdkDir, "cmake", "MODERN_USER_STYLES_CMAKE_VERSION")
        val cmake = sdkDir.resolve("cmake/$cmakeVersion/bin/${executableName("cmake")}")
        val ninja = sdkDir.resolve("cmake/$cmakeVersion/bin/${executableName("ninja")}")
        val ndkRoot = sdkDir.resolve("ndk/$ndkVersion")
        val nativeOut = outputDir.get().asFile

        sourceDir.resolve("scripts/build_ndk.properties").writeText(
            """
            WOFF2_REPO=https://github.com/google/woff2.git
            WOFF2_VERSION=v1.0.2
            ANDROID_SDK=${sdkDir.invariantSeparatorsPath}
            NDK_VERSION=$ndkVersion
            CMAKE_VERSION=$cmakeVersion
            MIN_ANDROID_SDK=21
            """.trimIndent(),
        )

        delete(nativeOut)
        nativeOut.mkdirs()

        val cppDir = sourceDir.resolve("libwoff2dec/src/main/cpp")
        val thirdPartyDir = cppDir.resolve("thirdparty")
        val sourcesDir = sourceDir.resolve("scripts/sources")
        val woff2Sources = sourcesDir.resolve("woff2")
        val installDir = sourcesDir.resolve("install")
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtMost(4).coerceAtLeast(1).toString()

        if (!woff2Sources.exists()) {
            sourcesDir.mkdirs()
            runCommand(
                sourcesDir,
                listOf(
                    "git",
                    "clone",
                    "--single-branch",
                    "--branch",
                    "v1.0.2",
                    "--recursive",
                    "https://github.com/google/woff2.git",
                ),
            )
        }

        listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").forEach { abi ->
            println("Building WOFF2 decoder dependencies for $abi")
            val brotliSourceDir = woff2Sources.resolve("brotli")
            val brotliBuildDir = brotliSourceDir.resolve("out/$abi")
            if (!brotliBuildDir.resolve("libbrotlicommon-static.a").exists() || !brotliBuildDir.resolve("libbrotlidec-static.a").exists()) {
                runCommand(
                    projectDir,
                    listOf(
                        cmake.absolutePath,
                        "-S", brotliSourceDir.absolutePath,
                        "-B", brotliBuildDir.absolutePath,
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_TOOLCHAIN_FILE=${ndkRoot.resolve("build/cmake/android.toolchain.cmake").invariantSeparatorsPath}",
                        "-DCMAKE_MAKE_PROGRAM=${ninja.invariantSeparatorsPath}",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        "-DANDROID_ABI=$abi",
                        "-DANDROID_NATIVE_API_LEVEL=21",
                        "-G", "Ninja",
                    ),
                )
                runCommand(projectDir, listOf(cmake.absolutePath, "--build", brotliBuildDir.absolutePath, "--config", "Release", "-j", cpuCount))
            }

            val woff2BuildDir = woff2Sources.resolve("out/$abi")
            if (!woff2BuildDir.resolve("libwoff2common.a").exists() || !woff2BuildDir.resolve("libwoff2dec.a").exists()) {
                runCommand(
                    projectDir,
                    listOf(
                        cmake.absolutePath,
                        "-S", woff2Sources.absolutePath,
                        "-B", woff2BuildDir.absolutePath,
                        "-DBUILD_SHARED_LIBS=OFF",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DBROTLIDEC_INCLUDE_DIRS=${brotliSourceDir.resolve("c/include").invariantSeparatorsPath}",
                        "-DBROTLIDEC_LIBRARIES=${brotliBuildDir.resolve("libbrotlidec.so").invariantSeparatorsPath}",
                        "-DBROTLIENC_INCLUDE_DIRS=${brotliSourceDir.resolve("c/include").invariantSeparatorsPath}",
                        "-DBROTLIENC_LIBRARIES=${brotliBuildDir.resolve("libbrotlienc.so").invariantSeparatorsPath}",
                        "-DCMAKE_TOOLCHAIN_FILE=${ndkRoot.resolve("build/cmake/android.toolchain.cmake").invariantSeparatorsPath}",
                        "-DCMAKE_MAKE_PROGRAM=${ninja.invariantSeparatorsPath}",
                        "-DANDROID_ABI=$abi",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        "-DANDROID_NATIVE_API_LEVEL=21",
                        "-G", "Ninja",
                    ),
                )
                runCommand(projectDir, listOf(cmake.absolutePath, "--build", woff2BuildDir.absolutePath, "--config", "Release", "--target", "woff2common", "-j", cpuCount))
                runCommand(projectDir, listOf(cmake.absolutePath, "--build", woff2BuildDir.absolutePath, "--config", "Release", "--target", "woff2dec", "-j", cpuCount))
            }

            copy {
                from(brotliSourceDir.resolve("c/include"))
                into(thirdPartyDir.resolve("brotli/$abi/include"))
                include("**/*.h")
            }
            copy {
                from(
                    brotliBuildDir.resolve("libbrotlicommon-static.a"),
                    brotliBuildDir.resolve("libbrotlidec-static.a"),
                )
                into(thirdPartyDir.resolve("brotli/$abi/lib"))
            }
            copy {
                from(woff2Sources.resolve("include"))
                into(thirdPartyDir.resolve("woff2/$abi/include"))
                include("**/*.h")
            }
            copy {
                from(
                    woff2BuildDir.resolve("libwoff2common.a"),
                    woff2BuildDir.resolve("libwoff2dec.a"),
                )
                into(thirdPartyDir.resolve("woff2/$abi/lib"))
            }

            val abiBuildDir = layout.buildDirectory.dir("woff2decoder_cmake/$abi").get().asFile
            val abiOutDir = nativeOut.resolve(abi).apply { mkdirs() }

            runCommand(
                projectDir,
                listOf(
                    cmake.absolutePath,
                    "-S", cppDir.absolutePath,
                    "-B", abiBuildDir.absolutePath,
                    "-DCMAKE_TOOLCHAIN_FILE=${ndkRoot.resolve("build/cmake/android.toolchain.cmake").invariantSeparatorsPath}",
                    "-DCMAKE_MAKE_PROGRAM=${ninja.invariantSeparatorsPath}",
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_NATIVE_API_LEVEL=21",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${abiOutDir.invariantSeparatorsPath}",
                    "-G", "Ninja",
                ),
            )
            runCommand(projectDir, listOf(cmake.absolutePath, "--build", abiBuildDir.absolutePath, "--config", "Release", "--target", "woff2decoder"))
        }
    }
}

fun appendModernUserStyleResources() {
    val output = layout.buildDirectory.file("outputs/ModernUserStyles.zip").get().asFile
    val packageDir = layout.projectDirectory.dir("src/main/modern_user_styles_package").asFile
    val nativeDir = woff2NativeOutputDir.get().asFile
    if (!output.exists() || !packageDir.exists() || !nativeDir.exists()) return

    val temp = output.resolveSibling("${output.name}.tmp")
    ZipInputStream(output.inputStream().buffered()).use { input ->
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            while (true) {
                val entry = input.nextEntry ?: break
                zip.putNextEntry(ZipEntry(entry.name))
                input.copyTo(zip)
                zip.closeEntry()
                input.closeEntry()
            }

            packageDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val name = packageDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    if (name.startsWith("native/")) return@forEach
                    zip.putNextEntry(ZipEntry("modern_user_styles/$name"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

            nativeDir.walkTopDown()
                .filter { it.isFile && it.name == "libwoff2decoder.so" }
                .forEach { file ->
                    val name = nativeDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry("modern_user_styles/native/$name"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
    temp.copyTo(output, overwrite = true)
    temp.delete()
}

tasks.named("make") {
    dependsOn(buildWoff2DecoderFromSource)
    doLast {
        appendModernUserStyleResources()
    }
}

aliucord {
    changelog.set(
        """
        # 0.0.27
        * Gate role gradients and holographic role colors on the server's Enhanced Role Styles perk instead of treating booster roles specially.

        # 0.0.26
        * Keep premium subscriber roles from creating false chat gradients while preserving their primary color.

        # 0.0.25
        * Prevent reflected legacy role color data from creating false chat gradients.

        # 0.0.24
        * Fix profile effect animations being wiped by follow-up renders.

        # 0.0.23
        * Restore Discord web's separate horizontal role-gradient shader for chat and limit profile animations to exact animated effects.

        # 0.0.22
        * Fix toon profile names to match Discord web's larger candy-fill gradient and add short profile effect animations.

        # 0.0.21
        * Fix customized profile display names collapsing to ellipses.

        # 0.0.20
        * Match Discord web gradient direction, color contrast, strokes, and layered profile name effects more closely.

        # 0.0.19
        * Show custom display-name colors and effects on profiles while keeping chat and member-list names role-colored.

        # 0.0.18
        * Apply reply name styles before Discord calculates reply preview spacing.

        # 0.0.17
        * Fix styled reply names overlapping the replied-to message text.

        # 0.0.16
        * Make role gradients closer to Discord web.
        * Fix member-list users sometimes showing as null.

        # 0.0.15
        * Link the author metadata to my Discord profile.

        # 0.0.14
        * Update public-facing plugin metadata and settings copy.

        # 0.0.13
        * Prepare the plugin for public repository review.
        * Remove debug slash commands and noisy profile logging.
        * Update package, author metadata, and plugin description.

        # 0.0.12
        * Ignore legacy fallback member colors unless they resolve to an actual colored guild role, keeping roleless users white.
        * Preserve server nicknames during async profile refreshes instead of replacing them with global display names.
        * Render role colors without display-name glow/neon effects that Discord web does not apply to roles.

        # 0.0.11
        * Keep users with no colored role white instead of falling back to display-name style colors.

        # 0.0.10
        * Load Discord's real WOFF2 display-name fonts with woff2-android instead of Android approximations.
        * Match the current Discord web font-class mapping and letter spacing.
        * Let role colors/gradients stay authoritative when a user also has a custom font.

        # 0.0.9
        * Style member-list UsernameView's internal username text so side-menu gradients apply.
        * Style replied-to usernames in reply previews.
        * Stop changing text weight, text scale, or letter spacing for gradients/effects.

        # 0.0.8
        * Resolve member-list role gradients from the real guild member roles instead of only item.color.
        * Use selected guild fallback for member-list role/color fetches.

        # 0.0.7
        * Resolve role colors by highest colored role position, matching Discord role precedence.
        * Let higher solid roles override lower gradient roles and improve per-font Android styling.

        # 0.0.6
        * Rework name rendering to apply color, gradient, shadow, and typeface directly to TextViews.
        * Make font ID fallbacks more visually distinct on Android.

        # 0.0.5
        * Fetch display-name styles through the modern popout profile route with guild context.
        * Cache profile fetches by user and guild so a no-style lightweight response does not block styled data.

        # 0.0.4
        * Apply single-color display-name styles for effects and font styling.
        * Parse display_name_styles anywhere in profile payloads and avoid member-list guildId crashes.

        # 0.0.3
        * Make gradients higher contrast and easier to read on small text.
        * Reset unstyled names to white instead of inheriting Discord's blue tint.

        # 0.0.2
        * Fetch modern profile display-name data and role color payloads directly for chat/member/profile surfaces.
        * Only render colors when Discord sends an actual gradient; custom fonts still render without colors.

        # 0.0.1
        * Initial ModernUserStyles build.
        * Render display names, display-name style IDs, and real role gradients where legacy data exposes them.
        """.trimIndent(),
    )
    deploy.set(true)
}
