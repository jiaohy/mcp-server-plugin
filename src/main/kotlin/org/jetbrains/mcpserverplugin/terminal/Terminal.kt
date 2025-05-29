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
        val lines = output.lines().map { it.trim() }.filter { it.isNotBlank() }
        // Regex pattern to match common terminal prompts:
        // Example: (base) user@host:path$ or user@host:path%
        val promptRegex = Regex("""^.*[a-zA-Z0-9_.\-]+[:@].*\s?[#\$%>] (.*)""")
        var latestPromptIndex = -1
        for ((index, line) in lines.withIndex()) {
            if (promptRegex.matches(line.trim()) && index != lines.lastIndex) {
                latestPromptIndex = index
            }
        }

        val resultLines = if (latestPromptIndex == -1) lines else lines.subList(latestPromptIndex, lines.lastIndex)
        return if (resultLines.size > maxLineCount) {
            resultLines.take(maxLineCount).joinToString("\n") + "\n... (output truncated at ${maxLineCount} resultLines)"
        } else {
            resultLines.joinToString("\n")
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

                val formattedOutput = formatOutput(output)
                val finalOutput = if (isInterrupted) {
                    "$formattedOutput\n... (Command execution interrupted after $timeout milliseconds)"
                } else {
                    formattedOutput
                }

                if (finalOutput.contains("./gradlew assembleDebug") && finalOutput.contains("BUILD FAILED")) {
                  // for assembleDebug tool, if the result is BUILD FAILED, set the response to error
                  future.complete(Response(error = finalOutput))
                } else {
                  future.complete(Response(finalOutput))
                }
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

// TODO - replace with future specialized tool(s) for building in Android Studio.
class AssembleDebug : AbstractMcpTool<NoArgs>() {
  override val name: String = "assemble_debug"
  override val description: String = """
        Runs `./gradlew assembleDebug` from the command line.
        Important features and limitations:
        - Checks if process is running before collecting output
        - Limits output to $maxLineCount lines (truncates excess)
        - Times out after $timeout milliseconds with notification
        Returns possible responses:
        - Terminal output (truncated if >$maxLineCount lines)
        - Output with interruption notice if timed out
        - Error messages for various failure cases
    """

  override fun handle(project: Project, args: NoArgs): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(
      project,
      ExecuteTerminalCommandArgs("./gradlew assembleDebug --stacktrace"),
    )
  }
}

// TODO - replace with future specialized tool(s) for running unit tests in Android Studio.
class RunUnitTestsForDebugVariants : AbstractMcpTool<NoArgs>() {
  override val name: String = "run_unit_tests_for_debug_variants"
  override val description: String = """
        Runs unit tests for all debuggable variants from the command line.
        Important features and limitations:
        - Checks if process is running before collecting output
        - Limits output to $maxLineCount lines (truncates excess)
        - Times out after $timeout milliseconds with notification
        Returns possible responses:
        - Terminal output (truncated if >$maxLineCount lines)
        - Output with interruption notice if timed out
        - Error messages for various failure cases
    """

  override fun handle(project: Project, args: NoArgs): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(
      project,
      ExecuteTerminalCommandArgs(
        "./gradlew -q tasks --all | cut -d ' ' -f1 | grep -E '^\\S*:test[^:]*DebugUnitTest' | xargs ./gradlew --stacktrace"
      ),
    )
  }
}

// TODO - replace with future specialized tool(s) for running android tests in Android Studio.
class RunAndroidTestsForDebugVariants : AbstractMcpTool<NoArgs>() {
  override val name: String = "run_android_tests_for_debug_variants"
  override val description: String = """
        Runs android tests for all debuggable variants from the command line.
        An Android device or emulator must be running for this tool to work, which can be checked using the list_adb_devices tool.
        If no device is running, an emulator can be started with the run_emulator tool.
        Important features and limitations:
        - Checks if process is running before collecting output
        - Limits output to $maxLineCount lines (truncates excess)
        - Times out after $timeout milliseconds with notification
        Returns possible responses:
        - Terminal output (truncated if >$maxLineCount lines)
        - Output with interruption notice if timed out
        - Error messages for various failure cases
    """

  override fun handle(project: Project, args: NoArgs): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(
      project,
      ExecuteTerminalCommandArgs(
        "./gradlew -q tasks --all | cut -d ' ' -f1 | grep -E '^\\S*:connected[^:]*DebugAndroidTest' | xargs ./gradlew --stacktrace"
      ),
    )
  }
}

// TODO - don't assume $ANDROID_HOME is set.
class ListAdbDevices : AbstractMcpTool<NoArgs>() {
  override val name: String = "list_adb_devices"
  override val description: String = """
        Runs `adb devices` via the command line to show which Android devices (real or emulators) are currently running.
        Important features and limitations:
        - Checks if process is running before collecting output
        - Limits output to $maxLineCount lines (truncates excess)
        - Times out after $timeout milliseconds with notification
        Returns possible responses:
        - Terminal output (truncated if >$maxLineCount lines)
        - Output with interruption notice if timed out
        - Error messages for various failure cases
    """

  override fun handle(project: Project, args: NoArgs): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(
      project,
      ExecuteTerminalCommandArgs("\$ANDROID_HOME/platform-tools/adb devices"),
    )
  }
}

// TODO - don't assume $ANDROID_HOME is set.
class ListEmulators : AbstractMcpTool<NoArgs>() {
  override val name: String = "list_emulators"
  override val description: String = """
        Runs `emulator -list-avds` via the command line to show which emulators are available to run.
        Important features and limitations:
        - Checks if process is running before collecting output
        - Limits output to $maxLineCount lines (truncates excess)
        - Times out after $timeout milliseconds with notification
        Returns possible responses:
        - Terminal output (truncated if >$maxLineCount lines)
        - Output with interruption notice if timed out
        - Error messages for various failure cases
    """

  override fun handle(project: Project, args: NoArgs): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(
      project,
      ExecuteTerminalCommandArgs("\$ANDROID_HOME/emulator/emulator -list-avds"),
    )
  }
}

// TODO - don't assume $ANDROID_HOME is set.
class RunEmulator : AbstractMcpTool<RunEmulator.Args>() {
  override val name: String = "run_emulator"
  override val description: String =
    """
      Runs `emulator -avds <avdName>` via the command line to run the emulator with the given name.

      To see available emulators, use the list_emulators tool.
    """
      .trimIndent()

  override fun handle(project: Project, args: Args): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(
      project,
      ExecuteTerminalCommandArgs("\$ANDROID_HOME/emulator/emulator -avd ${args.avdName}"),
    )
  }

  @Serializable data class Args(val avdName: String)
}

class GitDiff : AbstractMcpTool<NoArgs>() {
  override val name: String = "git_diff"
  override val description: String = "Runs `git diff` from the command line."

  override fun handle(project: Project, args: NoArgs): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(project, ExecuteTerminalCommandArgs("git diff"))
  }
}

class GitDiffTwoShas : AbstractMcpTool<GitDiffTwoShas.Args>() {
  override val name: String = "git_diff_two_shas"
  override val description: String = "Runs `git diff <sha1> <sha2>` from the command line."

  override fun handle(project: Project, args: Args): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(
      project,
      ExecuteTerminalCommandArgs("git diff ${args.sha1} ${args.sha2}"),
    )
  }

  @Serializable data class Args(val sha1: String, val sha2: String)
}

class GitLog : AbstractMcpTool<NoArgs>() {
  override val name: String = "git_log"
  override val description: String = "Runs `git --no-pager log -n 5` from the command line."

  override fun handle(project: Project, args: NoArgs): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(project, ExecuteTerminalCommandArgs("git --no-pager log -n 5"))
  }
}

class GitAdd : AbstractMcpTool<NoArgs>() {
  override val name: String = "git_add"
  override val description: String = "Runs `git add .` from the command line."

  override fun handle(project: Project, args: NoArgs): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(project, ExecuteTerminalCommandArgs("git add ."))
  }
}

class GitCommit : AbstractMcpTool<GitCommit.Args>() {
  override val name: String = "git_commit"
  override val description: String = "Runs `git commit -m <message>` from the command line."

  override fun handle(project: Project, args: Args): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(
      project,
      ExecuteTerminalCommandArgs("git commit -m \"${args.message}\""),
    )
  }

  @Serializable data class Args(val message: String)
}

class GitCheckout : AbstractMcpTool<GitCheckout.Args>() {
  override val name: String = "git_checkout"
  override val description: String = "Runs `git checkout <sha>` from the command line."

  override fun handle(project: Project, args: Args): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(
      project,
      ExecuteTerminalCommandArgs("git checkout ${args.sha}"),
    )
  }

  @Serializable data class Args(val sha: String)
}

class GitStash : AbstractMcpTool<NoArgs>() {
  override val name: String = "git_stash"
  override val description: String = "Runs `git stash` from the command line."

  override fun handle(project: Project, args: NoArgs): Response {
    val executeTerminalCommandTool = ExecuteTerminalCommandTool()
    return executeTerminalCommandTool.handle(project, ExecuteTerminalCommandArgs("git stash"))
  }
}
