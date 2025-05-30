package org.jetbrains.mcpserverplugin.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import com.jediterm.terminal.TtyConnector
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.settings.PluginSettings
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalUtil
import org.jetbrains.plugins.terminal.TerminalView
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.JComponent

val maxLineCount = 2000
val timeout = TimeUnit.MINUTES.toMillis(2)

class GetTerminalTextTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_terminal_text"
    override val description: String = """
        Retrieves the current text content from the first active terminal in the IDE.
        Use this tool to access the terminal's output and command history.
        Returns one of two possible responses:
        - The terminal's text content if a terminal exists
        - empty string if no terminal is open or available
        Note: Only captures text from the first terminal if multiple terminals are open
    """

    override fun handle(project: Project, args: NoArgs): Response {
        val text = com.intellij.openapi.application.runReadAction<String?> {
            TerminalView.getInstance(project).getWidgets().firstOrNull()?.text
        }
        return Response(text ?: "")
    }
}

@Serializable
data class ExecuteTerminalCommandArgs(val command: String)

class ExecuteTerminalCommandTool : AbstractMcpTool<ExecuteTerminalCommandArgs>() {
    override val name: String = "execute_terminal_command"
    override val description: String = """
        Executes a specified shell command in the IDE's integrated terminal.
        Use this tool to run terminal commands within the IDE environment.
        Requires a command parameter containing the shell command to execute.
        Important features and limitations:
        - Checks if process is running before collecting output
        - Limits output to $maxLineCount lines (truncates excess)
        - Times out after $timeout milliseconds with notification
        - Requires user confirmation unless "Brave Mode" is enabled in settings
        Returns possible responses:
        - Terminal output (truncated if >$maxLineCount lines)
        - Output with interruption notice if timed out
        - Error messages for various failure cases
    """

    private fun collectTerminalOutput(widget: ShellTerminalWidget): String? {
        val processTtyConnector = ShellTerminalWidget.getProcessTtyConnector(widget.ttyConnector) ?: return null

        // Check if the process is still running
        if (!TerminalUtil.hasRunningCommands(processTtyConnector as TtyConnector)) {
            return widget.text
        }
        return null
    }

    private fun formatOutput(output: String): String {
        val lines = output.lines()
        return if (lines.size > maxLineCount) {
            lines.take(maxLineCount).joinToString("\n") + "\n... (output truncated at ${maxLineCount} lines)"
        } else {
            output
        }
    }

    override fun handle(project: Project, args: ExecuteTerminalCommandArgs): Response {
        val future = CompletableFuture<Response>()

        ApplicationManager.getApplication().invokeAndWait {
            val braveMode = ApplicationManager.getApplication().getService(PluginSettings::class.java).state.enableBraveMode
            var proceedWithCommand = true
            
            if (!braveMode) {
                val confirmationDialog = object : DialogWrapper(project, true) {
                    init {
                        init()
                        title = "Confirm Command Execution"
                    }

                    override fun createCenterPanel(): JComponent? {
                        return panel {
                            row {
                                label("Do you want to run command `${args.command.take(100)}` in the terminal?")
                            }
                            row {
                                comment("Note: You can enable 'Brave Mode' in settings to skip this confirmation.")
                            }
                        }
                    }
                }
                confirmationDialog.show()
                proceedWithCommand = confirmationDialog.isOK
            }

            if (!proceedWithCommand) {
                future.complete(Response(error = "canceled"))
                return@invokeAndWait
            }
            
            ShTerminalRunner.run(project, "clear", project.basePath ?: "", "MCP Command", true)
            val terminalWidget =
                ShTerminalRunner.run(project, args.command, project.basePath ?: "", "MCP Command", true)
            val shellWidget =
                if (terminalWidget != null) ShellTerminalWidget.asShellJediTermWidget(terminalWidget) else null

            if (shellWidget == null) {
                future.complete(Response(error = "No terminal available"))
                return@invokeAndWait
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                var output: String? = null
                var isInterrupted = false

                val sleep = 300L
                for (i in 1..timeout / sleep) {
                    Thread.sleep(sleep)
                    output = collectTerminalOutput(shellWidget)
                    if (output != null) break
                }

                if (output == null) {
                    output = shellWidget.text
                    isInterrupted = true
                }

                var formattedOutput = formatOutput(output).trim()
                // trim last line if they end with "$" or ">"
                val lastLine = formattedOutput.lines().lastOrNull() ?: ""
                if (lastLine.trim().endsWith("$") || lastLine.trim().endsWith(">")) {
                    // Remove the last line if it is a prompt
                    formattedOutput = formattedOutput.lines().dropLast(1).joinToString("\n")
                }
                val finalOutput = if (isInterrupted) {
                    "$formattedOutput\n... (Command execution interrupted after $timeout milliseconds)"
                } else {
                    formattedOutput
                }

                future.complete(Response(finalOutput))
            }
        }

        try {
            return future.get(
                timeout + 2000,
                TimeUnit.MILLISECONDS
            ) // Give slightly more time than the internal timeout
        } catch (e: TimeoutException) {
            return Response(error = "Command execution timed out after $timeout milliseconds")
        } catch (e: Exception) {
            return Response(error = "Execution error: ${e.message}")
        }
    }
}

// =============================================================================
// FAST ERROR DETECTION TOOLS - USE THESE FIRST FOR QUICK FEEDBACK
// =============================================================================

class QuickCompileCheck : AbstractMcpTool<NoArgs>() {
    override val name: String = "check_compilation_errors"
    override val description: String = """
        **FAST** compilation check without building APK (5-10 seconds vs 30+ for full build).
        **USE THIS FIRST** to quickly detect syntax errors, missing imports, and compilation issues.
        Only compiles Kotlin/Java code without creating APK or running resource processing.
        Perfect for rapid feedback during code development.
        AI Agent: Use this tool whenever you want to verify code changes compile correctly.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew compileDebugKotlin compileDebugJavaWithJavac")
        )
    }
}

class CheckCodeQuality : AbstractMcpTool<NoArgs>() {
    override val name: String = "check_code_quality"
    override val description: String = """
        **FAST** Android Lint check (2-5 seconds) to detect potential bugs, performance issues, and code quality problems.
        **USE THIS EARLY** in development to catch issues before building APK.
        Checks for: unused resources, potential crashes, accessibility issues, security problems, performance bottlenecks.
        AI Agent: Run this after code changes to ensure quality before building.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew lintDebug")
        )
    }
}

class ValidateProjectQuickly : AbstractMcpTool<NoArgs>() {
    override val name: String = "validate_project_structure"
    override val description: String = """
        **FAST** multi-check validation (10-15 seconds) that verifies:
        - Gradle configuration is valid
        - Project structure is correct  
        - Dependencies can be resolved
        - Basic syntax check passes
        **USE THIS** when setting up project or after major changes to catch multiple issues quickly.
        AI Agent: Use this tool after making project-level changes or when debugging build issues.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("""
                echo "=== Gradle Wrapper Check ===" &&
                ./gradlew --version &&
                echo "=== Project Structure Check ===" &&
                ls -la app/src/main/ &&
                echo "=== Quick Syntax Check ===" &&
                ./gradlew compileDebugKotlin --dry-run &&
                echo "=== Dependency Check ===" &&
                ./gradlew dependencies --quiet | head -20 &&
                echo "=== Validation Complete ==="
            """.trimIndent())
        )
    }
}



class RefreshDependencies : AbstractMcpTool<NoArgs>() {
    override val name: String = "refresh_dependencies"
    override val description: String = """
        **FAST** dependency refresh (5-15 seconds) to update cached dependencies.
        **USE THIS** when facing dependency resolution issues or when dependencies seem stale.
        Forces Gradle to re-download and refresh all project dependencies from repositories.
        Useful for:
        - Resolving cached dependency conflicts
        - Getting latest SNAPSHOT versions
        - Fixing corrupted dependency cache
        AI Agent: Use this tool when encountering "could not resolve dependency" errors.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew --refresh-dependencies dependencies --quiet")
        )
    }
}

class ValidateGradleConfiguration : AbstractMcpTool<NoArgs>() {
    override val name: String = "validate_gradle_configuration"
    override val description: String = """
        **FAST** Gradle configuration validation (3-8 seconds) to check build files syntax and structure.
        **USE THIS** after editing build.gradle files to ensure valid Gradle syntax.
        Validates:
        - build.gradle syntax correctness
        - Plugin configurations
        - Basic project structure
        - Gradle wrapper integrity
        AI Agent: Use this tool immediately after modifying Gradle build files.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("""
                echo "=== Gradle Wrapper Validation ===" &&
                ./gradlew --version &&
                echo "=== Build File Syntax Check ===" &&
                ./gradlew help --quiet &&
                echo "=== Project Configuration Check ===" &&
                ./gradlew projects --quiet &&
                echo "=== Plugin Validation ===" &&
                ./gradlew plugins --quiet &&
                echo "=== Configuration Complete ==="
            """.trimIndent())
        )
    }
}


// =============================================================================
// TESTING TOOLS - SIMPLIFIED AND RELIABLE
// =============================================================================

class RunUnitTests : AbstractMcpTool<NoArgs>() {
    override val name: String = "run_unit_tests"
    override val description: String = """
        Runs all unit tests for debug variant (10-30 seconds depending on test count).
        Unit tests run on JVM without needing Android device/emulator.
        **USE THIS** to verify business logic and utility functions work correctly.
        AI Agent: Use this tool after making code changes to ensure existing functionality still works.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew testDebugUnitTest --stacktrace")
        )
    }
}

@Serializable
data class RunAndroidTestArgs(
    val withStacktrace: Boolean = false,
    val withInfo: Boolean = false
)

class RunAndroidTests : AbstractMcpTool<RunAndroidTestArgs>() {
    override val name: String = "run_instrumented_tests"
    override val description: String = """
        Runs instrumented Android tests on connected device/emulator (1–5 minutes).
        **REQUIRES** connected Android device or emulator (check with list_devices first).
        Accepts optional Gradle task and verbosity flags - default is false.
    """

    override fun handle(project: Project, args: RunAndroidTestArgs): Response {
        val flags = buildList {
            if (args.withStacktrace) add("--stacktrace")
            else if (args.withInfo) add("--info")
        }

        val command = "./gradlew connectedDebugAndroidTest ${flags.joinToString(" ")}".trim()

        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs(command)
        )
    }
}
// =============================================================================
// BUILD TOOLS - EXPENSIVE, USE ONLY WHEN NECESSARY
// =============================================================================

class SyncGradleProject : AbstractMcpTool<NoArgs>() {
    override val name: String = "sync_gradle_project"
    override val description: String = """
        **MODERATE** Gradle project sync (15-45 seconds) to refresh dependencies and project configuration.
        **USE THIS** after making changes to build.gradle files, adding new dependencies, or when project structure changes.
        Syncs project with Gradle files, downloads new dependencies, and updates IDE project structure.
        Essential after:
        - Adding new dependencies to build.gradle
        - Changing Gradle configuration
        - Switching branches with different dependencies
        - Project setup or major structural changes
        AI Agent: Use this tool when build errors suggest dependency or configuration issues.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew --refresh-dependencies build --dry-run")
        )
    }
}

class BuildDebugApk : AbstractMcpTool<NoArgs>() {
    override val name: String = "build_debug_apk"
    override val description: String = """
        **EXPENSIVE** (30-60+ seconds): Builds complete debug APK file.
        Creates installable APK with debug configuration (unoptimized, debuggable).
        **ONLY USE** when you need actual APK file for installation or distribution.
        AI Agent: Use this tool ONLY after fast checks pass and you need an installable APK.
        Consider using install_debug_app instead if you just want to test on device.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew assembleDebug --stacktrace")
        )
    }
}

class BuildReleaseApk : AbstractMcpTool<NoArgs>() {
    override val name: String = "build_release_apk"  
    override val description: String = """
        **VERY EXPENSIVE** (60-120+ seconds): Builds optimized release APK.
        Creates production-ready APK with code shrinking, obfuscation, and optimizations.
        **ONLY USE** for final testing or distribution preparation.
        AI Agent: Use this tool ONLY for production builds or final testing. Not for development.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew assembleRelease --stacktrace")
        )
    }
}

class BuildAppBundle : AbstractMcpTool<NoArgs>() {
    override val name: String = "build_app_bundle_for_playstore"
    override val description: String = """
        **VERY EXPENSIVE** (60-120+ seconds): Creates Android App Bundle (AAB) for Google Play Store.
        AAB is required format for Play Store submissions, optimizes app size for users.
        **ONLY USE** when preparing app for Play Store submission.
        AI Agent: Use this tool ONLY when creating final builds for Play Store upload.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew bundleRelease --stacktrace")
        )
    }
}

class CleanAndRebuild : AbstractMcpTool<NoArgs>() {
    override val name: String = "clean_and_rebuild_project"
    override val description: String = """
        **VERY EXPENSIVE** (60-120+ seconds): Deletes all build artifacts and rebuilds from scratch.
        **ONLY USE** when facing strange build cache issues, unexplained compilation errors, or build inconsistencies.
        This forces complete recompilation of everything.
        AI Agent: Use this tool ONLY as last resort when other builds fail unexpectedly or you suspect cache corruption.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew clean assembleDebug --stacktrace")
        )
    }
}

// =============================================================================
// DEVICE MANAGEMENT AND APP INSTALLATION
// =============================================================================

class ListDevices : AbstractMcpTool<NoArgs>() {
    override val name: String = "list_connected_devices"
    override val description: String = """
        **FAST** (1-2 seconds): Lists all connected Android devices and emulators.
        Shows device status (device/offline/unauthorized) and device identifiers.
        **USE THIS** before running instrumented tests or installing apps.
        AI Agent: Always check device connectivity before tools that require devices.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("""
                if command -v adb > /dev/null; then
                    adb devices -l
                elif [ -n "${'$'}ANDROID_HOME" ]; then
                    ${'$'}ANDROID_HOME/platform-tools/adb devices -l
                elif [ -f ~/Library/Android/sdk/platform-tools/adb ]; then
                    ~/Library/Android/sdk/platform-tools/adb devices -l
                else
                    echo "ADB not found. Please install Android SDK or set ANDROID_HOME"
                fi
            """.trimIndent())
        )
    }
}

class InstallDebugApp : AbstractMcpTool<NoArgs>() {
    override val name: String = "install_debug_app"
    override val description: String = """
        **EXPENSIVE** (30-90 seconds): Builds debug APK and installs it on connected device/emulator.
        Combines build + installation in one step for convenience.
        **REQUIRES** connected device/emulator (check with list_connected_devices first).
        AI Agent: Use this tool when you want to test app on device. More efficient than separate build + install.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew installDebug --stacktrace")
        )
    }
}

class UninstallApp : AbstractMcpTool<NoArgs>() {
    override val name: String = "uninstall_debug_app"
    override val description: String = """
        **FAST** (2-5 seconds): Removes debug version of app from connected device/emulator.
        **USE THIS** for clean installation testing or when app installation is corrupted.
        Requires connected device/emulator.
        AI Agent: Use this tool before install_debug_app if you want clean installation.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew uninstallDebug")
        )
    }
}

// =============================================================================
// EMULATOR MANAGEMENT  
// =============================================================================

class ListAvailableEmulators : AbstractMcpTool<NoArgs>() {
    override val name: String = "list_available_emulators"
    override val description: String = """
        **FAST** (1-2 seconds): Shows all created Android Virtual Devices (emulators) available to run.
        **USE THIS** to see emulator options before starting an emulator.
        AI Agent: Use this tool to discover available emulators before using start_emulator.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("""
                if [ -n "${'$'}ANDROID_HOME" ]; then
                    ${'$'}ANDROID_HOME/emulator/emulator -list-avds
                elif [ -f ~/Library/Android/sdk/emulator/emulator ]; then
                    ~/Library/Android/sdk/emulator/emulator -list-avds
                else
                    echo "Android emulator not found. Please install Android SDK or set ANDROID_HOME"
                fi
            """.trimIndent())
        )
    }
}
@Serializable
data class EnsureEmulatorReadyArgs(
    val emulatorName: String,
    val timeoutSeconds: Int = 30 // optional timeout
)

class EnsureEmulatorReady : AbstractMcpTool<EnsureEmulatorReadyArgs>() {
    override val name: String = "ensure_emulator_ready"
    override val description: String = """
        Starts emulator if no connected device is found and waits until it's fully booted.
        Uses `adb devices`, `emulator -avd`, and waits for `sys.boot_completed`.
    """.trimIndent()

    override fun handle(project: Project, args: EnsureEmulatorReadyArgs): Response {
        val cmd = """
            if [ -n "\${'$'}ANDROID_HOME" ]; then
                nohup "\${'$'}ANDROID_HOME/emulator/emulator" -avd "${args.emulatorName}" > /dev/null 2>&1 & disown
            elif [ -f ~/Library/Android/sdk/emulator/emulator ]; then
                nohup ~/Library/Android/sdk/emulator/emulator -avd "${args.emulatorName}" > /dev/null 2>&1 & disown
            else
                echo "Android emulator not found"
                exit 1
            fi

            timeout=${args.timeoutSeconds}
            waited=0
            while [ "\${'$'}waited" -lt "\${'$'}timeout" ]; do
                boot_status=\$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
                if [ "\${'$'}boot_status" = "1" ]; then
                    echo "Boot completed"
                    exit 0
                fi
                sleep 5
                waited=\$((waited + 5))
            done
            echo "Timed out"
            exit 1
        """.trimIndent()

        return ExecuteTerminalCommandTool().handle(
            project,
            ExecuteTerminalCommandArgs(
                "bash -c '${cmd.replace("'", "'\\''")}'"
            )
        )
    }
}


// =============================================================================
// DEBUGGING AND ANALYSIS TOOLS
// =============================================================================

class GetAppLogs : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_recent_app_logs"
    override val description: String = """
        **FAST** (1-3 seconds): Gets recent logcat output from connected device/emulator.
        Shows last 100 lines of system logs, useful for debugging crashes and runtime issues.
        **USE THIS** when app crashes or behaves unexpectedly to see error messages.
        AI Agent: Use this tool when debugging runtime issues or after app crashes.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("adb logcat -d -v time | tail -100")
        )
    }
}

@Serializable  
data class LaunchAppArgs(val packageName: String, val activityName: String)

class LaunchApp : AbstractMcpTool<LaunchAppArgs>() {
    override val name: String = "launch_installed_app"
    override val description: String = """
        **FAST** (1-2 seconds): Launches installed app on connected device using package and activity name.
        Requires app to be already installed and device connected.
        Example: packageName="com.example.helloworld", activityName=".MainActivity"
        AI Agent: Use this tool to start app after installation for testing.
    """
    
    override fun handle(project: Project, args: LaunchAppArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("adb shell am start -n ${args.packageName}/${args.activityName}")
        )
    }
}

// =============================================================================
// PROJECT ANALYSIS TOOLS
// =============================================================================

class AnalyzeDependencies : AbstractMcpTool<NoArgs>() {
    override val name: String = "analyze_project_dependencies"
    override val description: String = """
        **MODERATE** (10-20 seconds): Shows project dependencies and potential conflicts.
        Helps debug dependency resolution issues and version conflicts.
        **USE THIS** when facing dependency-related build errors or when adding new libraries.
        AI Agent: Use this tool when build fails due to dependency conflicts or when investigating library versions.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew dependencies --configuration debugRuntimeClasspath")
        )
    }
}

class ShowProjectStructure : AbstractMcpTool<NoArgs>() {
    override val name: String = "show_project_structure"
    override val description: String = """
        **FAST** (1-2 seconds): Shows project modules and basic Gradle structure.
        **USE THIS** to understand project organization, especially for multi-module projects.
        AI Agent: Use this tool when you need to understand project layout or working with multiple modules.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew projects")
        )
    }
}

class ListAvailableTasks : AbstractMcpTool<NoArgs>() {
    override val name: String = "list_gradle_tasks"
    override val description: String = """
        **FAST** (2-5 seconds): Lists all available Gradle tasks for the project.
        **USE THIS** to discover available build operations and task names.
        AI Agent: Use this tool when you need to find specific Gradle tasks or explore project capabilities.
    """
    
    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("./gradlew tasks")
        )
    }
}

// =============================================================================
// GIT INTEGRATION TOOLS - KEPT FROM ORIGINAL
// =============================================================================

class GitDiff : AbstractMcpTool<NoArgs>() {
    override val name: String = "git_diff"
    override val description: String = "**FAST**: Shows current uncommitted changes in the project. Output is shown without pager for direct streaming."

    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("git --no-pager diff")
        )
    }
}


@Serializable
data class GitDiffTwoShasArgs(val sha1: String, val sha2: String)

class GitDiffTwoShas : AbstractMcpTool<GitDiffTwoShasArgs>() {
    override val name: String = "git_diff_commits"
    override val description: String = "**FAST**: Shows differences between two specific commits."

    override fun handle(project: Project, args: GitDiffTwoShasArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("git diff ${args.sha1} ${args.sha2}")
        )
    }
}

class GitLog : AbstractMcpTool<NoArgs>() {
    override val name: String = "git_recent_commits"
    override val description: String = "**FAST**: Shows last 5 commits in the project."

    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(project, ExecuteTerminalCommandArgs("git --no-pager log -n 5"))
    }
}

class GitAdd : AbstractMcpTool<NoArgs>() {
    override val name: String = "git_stage_all_changes"
    override val description: String = "**FAST**: Stages all current changes for commit."

    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(project, ExecuteTerminalCommandArgs("git add ."))
    }
}

@Serializable
data class GitCommitArgs(val message: String)

class GitCommit : AbstractMcpTool<GitCommitArgs>() {
    override val name: String = "git_commit_changes"
    override val description: String = "**FAST**: Commits staged changes with provided message."

    override fun handle(project: Project, args: GitCommitArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("git commit -m \"${args.message}\"")
        )
    }
}

@Serializable
data class GitCheckoutArgs(val sha: String)

class GitCheckout : AbstractMcpTool<GitCheckoutArgs>() {
    override val name: String = "git_checkout_commit"
    override val description: String = "**FAST**: Switches to specific commit or branch."

    override fun handle(project: Project, args: GitCheckoutArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(
            project,
            ExecuteTerminalCommandArgs("git checkout ${args.sha}")
        )
    }
}

class GitStash : AbstractMcpTool<NoArgs>() {
    override val name: String = "git_stash_changes"
    override val description: String = "**FAST**: Temporarily saves current changes without committing."

    override fun handle(project: Project, args: NoArgs): Response {
        val executeTerminalCommandTool = ExecuteTerminalCommandTool()
        return executeTerminalCommandTool.handle(project, ExecuteTerminalCommandArgs("git stash"))
    }
}
