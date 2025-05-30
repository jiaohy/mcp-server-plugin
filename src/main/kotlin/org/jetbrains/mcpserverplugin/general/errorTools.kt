package org.jetbrains.mcpserverplugin.general

import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.mcpserverplugin.JsonUtils
import java.nio.file.Path


class GetCurrentFileErrorsTool : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_current_file_errors"
    override val description: String = """
        Analyzes the currently open file in the editor for errors using IntelliJ's inspections.
        Use this tool to identify coding issues and syntax errors in your current file.
        Returns a JSON array of objects containing error information:
        - severity: The severity level (only "ERROR")
        - description: A description of the issue
        - lineContent: The content of the line containing the issue
        - lineNumber: The 1-based line number of the error
        Returns an empty array ([]) if no errors are found.
        Returns error if no file is currently open.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        return runReadAction {
            try {
                val fileEditorManager = FileEditorManager.getInstance(project)
                val editor = fileEditorManager.selectedTextEditor
                    ?: return@runReadAction Response(error = "no file open")

                val document = editor.document
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                    ?: return@runReadAction Response(error = "could not get PSI file")

                val highlightInfos = getHighlightInfos(project, psiFile, document)
                val errorInfos = formatErrorHighlightInfos(document, highlightInfos)

                Response(errorInfos.joinToString(",\n", prefix = "[", postfix = "]"))
            } catch (e: Exception) {
                Response(error = "Error analyzing file: ${e.message}")
            }
        }
    }

    private fun getHighlightInfos(project: Project, psiFile: PsiFile, document: Document): List<HighlightInfo> {
        val highlightInfos = mutableListOf<HighlightInfo>()
        DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.ERROR, 0, document.textLength) { highlightInfo ->
            highlightInfos.add(highlightInfo)
            true
        }
        return highlightInfos
    }

    private fun formatErrorHighlightInfos(document: Document, highlightInfos: List<HighlightInfo>): List<String> {
        return highlightInfos.map { info ->
            val startLine = document.getLineNumber(info.startOffset)
            val lineStartOffset = document.getLineStartOffset(startLine)
            val lineEndOffset = document.getLineEndOffset(startLine)
            val lineContent = document.getText(TextRange(lineStartOffset, lineEndOffset))
            """
            {
                "severity": "${info.severity}",
                "description": "${JsonUtils.escapeJson(info.description)}",
                "lineContent": "${JsonUtils.escapeJson(lineContent)}",
                "lineNumber": ${startLine + 1}
            }
            """.trimIndent()
        }
    }
}

@kotlinx.serialization.Serializable
data class GetFileErrorsArgs(val pathInProject: String)

class GetFileErrorsByPathTool : AbstractMcpTool<GetFileErrorsArgs>() {
    override val name: String = "get_file_errors_by_path"
    override val description: String = """
        Analyzes the specified file (by project-relative path) for errors (not warnings) using IntelliJ's inspections.
        Use this tool to identify coding issues and syntax errors in any file in the project.
        Parameters:
        - pathInProject: The path to the file, relative to the project root.
        Returns a JSON array of objects containing error information:
        - severity: The severity level (only "ERROR")
        - description: A description of the issue
        - lineContent: The content of the line containing the issue
        - lineNumber: The 1-based line number of the error
        Returns an empty array ([]) if no errors are found.
        Returns error if the file is not found or cannot be analyzed.
    """.trimIndent()

    override fun handle(project: Project, args: GetFileErrorsArgs): Response {
        return runReadAction {
            try {
                val projectDir = project.guessProjectDir()?.toNioPathOrNull()
                    ?: return@runReadAction Response(error = "project dir not found")

                val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(projectDir.resolveRel(args.pathInProject))
                    ?: return@runReadAction Response(error = "file not found")

                val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vFile)
                    ?: return@runReadAction Response(error = "could not get document")

                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile)
                    ?: return@runReadAction Response(error = "could not get PSI file")

                val highlightInfos = getHighlightInfos(project, psiFile, document)
                val errorInfos = formatErrorHighlightInfos(document, highlightInfos)

                Response(errorInfos.joinToString(",\n", prefix = "[", postfix = "]"))
            } catch (e: Exception) {
                Response(error = "Error analyzing file: ${e.message}")
            }
        }
    }

    private fun getHighlightInfos(project: Project, psiFile: PsiFile, document: Document): List<HighlightInfo> {
        val highlightInfos = mutableListOf<HighlightInfo>()
        DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.ERROR, 0, document.textLength) { highlightInfo ->
            highlightInfos.add(highlightInfo)
            true
        }
        return highlightInfos
    }

    private fun formatErrorHighlightInfos(document: Document, highlightInfos: List<HighlightInfo>): List<String> {
        return highlightInfos.map { info ->
            val startLine = document.getLineNumber(info.startOffset)
            val lineStartOffset = document.getLineStartOffset(startLine)
            val lineEndOffset = document.getLineEndOffset(startLine)
            val lineContent = document.getText(TextRange(lineStartOffset, lineEndOffset))
            """
            {
                "severity": "${info.severity}",
                "description": "${JsonUtils.escapeJson(info.description)}",
                "lineContent": "${JsonUtils.escapeJson(lineContent)}",
                "lineNumber": ${startLine + 1}
            }
            """.trimIndent()
        }
    }
}

class GetProblemsTools : AbstractMcpTool<NoArgs>() {
    override val name: String = "get_project_problems"
    override val description: String = """
        Retrieves all project problems (errors, warnings, etc.) detected in the project by IntelliJ's inspections.
        Use this tool to get a comprehensive list of global project issues (compilation errors, inspections problems, etc.).
        Does not require any parameters.
        
        Use another tool get_current_file_errors to get errors in the opened file. 
        
        Returns a JSON array of objects containing problem information:
        - group: The group or category of the problem
        - description: The location and description of the problem
        - problemText: The short text of the problem 
        
        Returns an empty array ([]) if no problems are found.
        Returns error "project dir not found" if the project directory cannot be determined.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        val collector = project.service<ProblemsCollector>()
        val problems = collector.getProblemFiles().map { collector.getFileProblems(it) }.flatten() + collector.getOtherProblems()
        val problemsFormatted = formatProjectProblems(projectDir, problems)
        return Response(problemsFormatted.joinToString(",\n", prefix = "[", postfix = "]"))
    }

    private fun formatProjectProblems(projectDir: Path, problems: List<Problem>): List<String> {
        return problems.map { problem ->
            """
            {
                "group": "${problem.group ?: ""}",
                "description": "${JsonUtils.escapeJson(problem.description?.removePrefix(projectDir.toAbsolutePath().toString())?.trimStart('/') ?: "")}",
                "problemText": "${JsonUtils.escapeJson(problem.text)}",
            }
            """.trimIndent()
        }
    }
}