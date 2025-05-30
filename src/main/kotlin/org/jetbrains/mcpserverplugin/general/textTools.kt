package org.jetbrains.mcpserverplugin.general

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManager.getInstance
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.application
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.general.relativizeByProjectDir
import org.jetbrains.mcpserverplugin.general.resolveRel
import org.jetbrains.mcpserverplugin.general.errorTools.GetFileErrorsArgs
import org.jetbrains.mcpserverplugin.general.errorTools.GetFileErrorsByPathTool

class GetCurrentFileTextTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_open_in_editor_file_text"
    override val description: String = """
        Retrieves the complete text content and relative path of the currently active file in the JetBrains IDE editor.
        Use this tool to access and analyze the file's contents and location for tasks such as code review, content inspection, or text processing.
        Returns an empty JSON object if no file is currently open.
        Response format: {"path": "<relative_path>", "text": "<file_content>"}
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val document = editor?.document
        val virtualFile = document?.let { FileDocumentManager.getInstance().getFile(it) }
        val text = document?.text
        val relPath = virtualFile?.toNioPathOrNull()?.relativizeByProjectDir(projectDir)
        return if (text != null && relPath != null) {
            Response("""{"path": "$relPath", "text": ${text.trimIndent().replace("\"", "\\\"").replace("\n", "\\n")}}""")
        } else {
            Response("{}")
        }
    }
}

class GetAllOpenFileTextsTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_all_open_file_texts"
    override val description: String = """
        Returns text of all currently open files in the JetBrains IDE editor.
        Returns an empty list if no files are open.
        
        Use this tool to explore current open editors.
        Returns a JSON array of objects containing file information:
            - path: Path relative to project root
            - text: File text
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()

        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val filePaths = openFiles.mapNotNull {
            """{"path": "${
                it.toNioPath().relativizeByProjectDir(projectDir)
            }", "text": "${it.readText()}", """
        }
        return Response(filePaths.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

class GetSelectedTextTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_selected_in_editor_text"
    override val description: String = """
        Retrieves the currently selected text from the active editor in JetBrains IDE.
        Use this tool when you need to access and analyze text that has been highlighted/selected by the user.
        Returns an empty string if no text is selected or no editor is open.
    """

    override fun handle(project: Project, args: NoArgs): Response {
        val text = runReadAction<String?> {
            FileEditorManager.getInstance(project).selectedTextEditor?.selectionModel?.selectedText
        }
        return Response(text ?: "")
    }
}

@Serializable
data class ReplaceSelectedTextArgs(val text: String)

class ReplaceSelectedTextTool : AbstractMcpTool<ReplaceSelectedTextArgs>() {
    override val name: String = "replace_selected_text"
    override val description: String = """
        Replaces the currently selected text in the active editor with specified new text.
        Use this tool to modify code or content by replacing the user's text selection.
        Requires a text parameter containing the replacement content.
        Returns one of three possible responses:
            - "ok" if the text was successfully replaced
            - "no text selected" if no text is selected or no editor is open
            - "unknown error" if the operation fails
    """.trimIndent()

    override fun handle(project: Project, args: ReplaceSelectedTextArgs): Response {
        var response: Response? = null

        application.invokeAndWait {
            runWriteCommandAction(project, "Replace Selected Text", null, {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                val document = editor?.document
                val selectionModel = editor?.selectionModel
                if (document != null && selectionModel != null && selectionModel.hasSelection()) {
                    document.replaceString(selectionModel.selectionStart, selectionModel.selectionEnd, args.text)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    response = Response("ok")
                } else {
                    response = Response(error = "no text selected")
                }
            })
        }

        return response ?: Response(error = "unknown error")
    }
}

// Remove this as this is error prone and not needed

// @Serializable
// data class ReplaceCurrentFileTextArgs(val text: String)

// class ReplaceCurrentFileTextTool : AbstractMcpTool<ReplaceCurrentFileTextArgs>() {
//     override val name: String = "replace_current_file_text"
//     override val description: String = """
//         Replaces the entire content of the currently active file in the JetBrains IDE with specified new text.
//         Use this tool when you need to completely overwrite the current file's content.
//         Requires a text parameter containing the new content.
//         Returns one of three possible responses:
//         - "ok" if the file content was successfully replaced
//         - "no file open" if no editor is active
//         - "unknown error" if the operation fails
//     """

//     override fun handle(project: Project, args: ReplaceCurrentFileTextArgs): Response {
//         var response: Response? = null
//         application.invokeAndWait {
//             runWriteCommandAction(project, "Replace File Text", null, {
//                 val editor = FileEditorManager.getInstance(project).selectedTextEditor
//                 val document = editor?.document
//                 if (document != null) {
//                     document.setText(args.text)
//                     response = Response("ok")
//                 } else {
//                     response = Response(error = "no file open")
//                 }
//             })
//         }
//         return response ?: Response(error = "unknown error")
//     }
// }

@Serializable
data class PathInProject(val pathInProject: String)

class GetFileTextByPathTool : AbstractMcpTool<PathInProject>() {
    override val name: String = "get_file_text_by_path"
    override val description: String = """
        Retrieves the text content of a file using its path relative to project root.
        Use this tool to read file contents when you have the file's project-relative path.
        Requires a pathInProject parameter specifying the file location from project root.
        Returns one of these responses:
        - The file's content if the file exists and belongs to the project
        - error "project dir not found" if project directory cannot be determined
        - error "file not found" if the file doesn't exist or is outside project scope
        Note: Automatically refreshes the file system before reading
    """

    override fun handle(project: Project, args: PathInProject): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        val text = runReadAction {
            val file = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(projectDir.resolveRel(args.pathInProject))
                ?: return@runReadAction Response(error = "file not found")

            if (GlobalSearchScope.allScope(project).contains(file)) {
                Response(file.readText())
            } else {
                Response(error = "file not found")
            }
        }
        return text
    }
}

@Serializable
data class GetFileLineRangeArgs(
    val pathInProject: String,
    val fromLine: Int? = 1, // 1-based, inclusive
    val numLines: Int? = null,   // 1-based, inclusive
    val withLineNumbers: Boolean = false
)

class GetFileLineRangeTool : AbstractMcpTool<GetFileLineRangeArgs>() {
    override val name: String = "get_file_line_range"
    override val description: String = """
        Retrieves a specific line range from a file using its path relative to the project root.
        Parameters:
        - pathInProject: Path to the file, relative to project root (required)
        - fromLine: Start line number (1-based, inclusive, optional). If not provided, defaults to 1.
        - numLines: Number of lines to retrieve (optional, if not provided, retrieves until the end of the file)
        - withLineNumbers: If true, prefixes each line with its line number (default: false)
        Returns:
        - JSON object: {"path": "<relative_path>", "text": "<file_content>"}
        - error "project dir not found" if project directory cannot be determined
        - error "file not found" if the file doesn't exist or is outside project scope
        - error "invalid line range" if the range is invalid
        Note: Automatically refreshes the file system before reading.
    """.trimIndent()

    override fun handle(project: Project, args: GetFileLineRangeArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        return runReadAction {
            val file = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(projectDir.resolveRel(args.pathInProject))
                ?: return@runReadAction Response(error = "file not found")

            if (!GlobalSearchScope.allScope(project).contains(file)) {
                return@runReadAction Response(error = "file not found")
            }

            val allLines = file.readText().lines()
            val from = args.fromLine?.coerceAtLeast(1) ?: 1
            val to = if (args.numLines != null) {
                (from + args.numLines - 1).coerceAtMost(allLines.size)
            } else {
                allLines.size
            }

            if (from > to || from > allLines.size) {
                return@runReadAction Response(error = "invalid line range")
            }

            val selectedLines = allLines.subList(from - 1, to)
            val content = if (args.withLineNumbers) {
                selectedLines.mapIndexed { idx, line -> "${from + idx}: $line" }.joinToString("\n")
            } else {
                selectedLines.joinToString("\n")
            }
            Response("""{"path": "${args.pathInProject}", "text": "${content.replace("\"", "\\\"").replace("\n", "\\n")}"}""")
        }
    }
}

@Serializable
data class ReplaceSpecificTextArgs(val pathInProject: String, val oldText: String, val newText: String)

class ReplaceSpecificTextTool : AbstractMcpTool<ReplaceSpecificTextArgs>() {
    override val name: String = "replace_specific_text"
    override val description: String = """
        Replaces specific text occurrences in a file with new text.
        Use this tool to make targeted changes without replacing the entire file content.
        Use this method if the file is large and the change is smaller than the old text.
        Prioritize this tool among other editing tools. It's more efficient and granular in the most of cases.
        Requires three parameters:
        - pathInProject: The path to the target file, relative to project root
        - oldText: The text to be replaced
        - newText: The replacement text
        Returns one of these responses:
        - JSON object: {"path": "<relative_path>", "text": "<new_file_content>"}
        - error "project dir not found" if project directory cannot be determined
        - error "file not found" if the file doesn't exist
        - error "could not get document" if the file content cannot be accessed
        - error "no occurrences found" if the old text was not found in the file
        - error "empty oldText not allowed"
        - error "replacement would drastically change line count"
        Note: Automatically saves the file after modification
        Additionally, always returns the file's error/warning info after modification.
    """.trimIndent()

    override fun handle(project: Project, args: ReplaceSpecificTextArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        // Check for empty oldText
        if (args.oldText.isBlank()) {
            return Response(error = "empty oldText not allowed")
        }

        var document: Document? = null

        val readResult = runReadAction {
            val file: VirtualFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(projectDir.resolveRel(args.pathInProject))
                ?: return@runReadAction "file not found"

            if (!GlobalSearchScope.allScope(project).contains(file)) {
                return@runReadAction "file not found"
            }

            document = FileDocumentManager.getInstance().getDocument(file)
            if (document == null) {
                return@runReadAction "could not get document"
            }

            return@runReadAction "ok"
        }

        if (readResult != "ok") {
            return Response(error = readResult)
        }

        val oldText = document!!.text
        if (!oldText.contains(args.oldText)) {
            return Response(error = "no occurrences found")
        }

        val newText = oldText.replace(args.oldText, args.newText, true)

        // Check for drastic file size increase (e.g., more than double)
        if (newText.length > oldText.length * 2) {
            return Response(error = "replacement would double file size")
        }

        // Check for drastic line count change (e.g., more than 2x or less than half)
        val oldLines = oldText.lines().size
        val newLines = newText.lines().size
        if (newLines > oldLines * 2 || newLines < oldLines / 2) {
            return Response(error = "replacement would drastically change line count")
        }

        WriteCommandAction.runWriteCommandAction(project) {
            document!!.setText(newText)
            FileDocumentManager.getInstance().saveDocument(document!!)
        }

        // Always return file errors after modification
        val errorTool = GetFileErrorsByPathTool()
        return errorTool.handle(project, GetFileErrorsArgs(args.pathInProject))
    }
}

@Serializable
data class ReplaceTextByPathToolArgs(val pathInProject: String, val text: String)

class ReplaceTextByPathTool : AbstractMcpTool<ReplaceTextByPathToolArgs>() {
    override val name: String = "replace_file_text_by_path"
    override val description: String = """
        Replaces the entire content of a specified file with new text, if the file is within the project.
        Use this tool to modify file contents using a path relative to the project root.
        Requires two parameters:
        - pathInProject: The path to the target file, relative to project root
        - text: The new content to write to the file
        Returns one of these responses:
        - "ok" if the file was successfully updated
        - error "project dir not found" if project directory cannot be determined
        - error "file not found" if the file doesn't exist
        - error "could not get document" if the file content cannot be accessed
        Note: Automatically saves the file after modification
        Additionally, always returns the file's error/warning info after modification.
    """.trimIndent()

    override fun handle(project: Project, args: ReplaceTextByPathToolArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        var document: Document? = null

        val readResult = runReadAction {
            var file: VirtualFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(projectDir.resolveRel(args.pathInProject))
                ?: return@runReadAction "file not found"

            if (!GlobalSearchScope.allScope(project).contains(file)) {
                return@runReadAction "file not found"
            }

            document = FileDocumentManager.getInstance().getDocument(file)
            if (document == null) {
                return@runReadAction "could not get document"
            }

            return@runReadAction "ok"
        }

        if (readResult != "ok") {
            return Response(error = readResult)
        }

        WriteCommandAction.runWriteCommandAction(project) {
            document!!.setText(args.text)
            FileDocumentManager.getInstance().saveDocument(document!!)
        }

        // Always return file errors after modification
        val errorTool = GetFileErrorsByPathTool()
        return errorTool.handle(project, GetFileErrorsArgs(args.pathInProject))
    }
}
