package com.rixit.claude.agent

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.rixit.claude.api.ApiContent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Runs the tools defined in [AgentTools]. All file paths are resolved against
 * the project base path and rejected if they escape it. Writes go through
 * [WriteConfirmer]; reads run inline.
 */
object ToolExecutor {

    /** Cap on bytes returned by read_file — keep token usage sane. */
    private const val MAX_READ_BYTES = 100 * 1024

    /** Cap on entries returned by list_files. */
    private const val MAX_LIST_ENTRIES = 500

    fun execute(
        project: Project,
        call: ApiContent.ToolUse,
        confirmer: WriteConfirmer
    ): ToolResult {
        return try {
            when (call.name) {
                "read_file"  -> readFile(project, call.input)
                "list_files" -> listFiles(project, call.input)
                "edit_file"  -> editFile(project, call.input, confirmer)
                "write_file" -> writeFile(project, call.input, confirmer)
                else -> ToolResult.error("Unknown tool: ${call.name}")
            }
        } catch (e: Exception) {
            ToolResult.error("Tool '${call.name}' failed: ${e.message}")
        }
    }

    // ---- read_file ----------------------------------------------------------

    private fun readFile(project: Project, input: JsonObject): ToolResult {
        val path = requireString(input, "path")
        val resolved = resolveProjectPath(project, path)
        if (!Files.exists(resolved)) return ToolResult.error("File not found: $path")
        if (!Files.isRegularFile(resolved)) return ToolResult.error("Not a regular file: $path")

        val bytes = Files.readAllBytes(resolved)
        val truncated = bytes.size > MAX_READ_BYTES
        val effective = if (truncated) bytes.copyOf(MAX_READ_BYTES) else bytes
        val text = String(effective, StandardCharsets.UTF_8)

        val sb = StringBuilder()
        val lines = text.split('\n')
        val width = lines.size.toString().length
        for ((i, line) in lines.withIndex()) {
            sb.append(String.format("%${width}d\t", i + 1)).append(line).append('\n')
        }
        if (truncated) {
            sb.append("\n[... file truncated at $MAX_READ_BYTES bytes; total ${bytes.size} bytes ...]")
        }
        return ToolResult.ok(sb.toString())
    }

    // ---- list_files ---------------------------------------------------------

    private fun listFiles(project: Project, input: JsonObject): ToolResult {
        val dirRel = requireString(input, "directory")
        val recursive = optBool(input, "recursive", false)
        val resolved = resolveProjectPath(project, dirRel)
        if (!Files.exists(resolved)) return ToolResult.error("Directory not found: $dirRel")
        if (!Files.isDirectory(resolved)) return ToolResult.error("Not a directory: $dirRel")

        val base = projectBase(project)
        val entries = mutableListOf<String>()
        val stream = if (recursive) Files.walk(resolved) else Files.list(resolved)
        stream.use { s ->
            for (p in s) {
                if (p == resolved) continue
                if (entries.size >= MAX_LIST_ENTRIES) {
                    entries.add("[... truncated at $MAX_LIST_ENTRIES entries ...]")
                    break
                }
                val rel = base.relativize(p).toString().replace('\\', '/')
                entries.add(if (Files.isDirectory(p)) "$rel/" else rel)
            }
        }
        entries.sort()
        return ToolResult.ok(entries.joinToString("\n"))
    }

    // ---- edit_file ----------------------------------------------------------

    private fun editFile(project: Project, input: JsonObject, confirmer: WriteConfirmer): ToolResult {
        val path = requireString(input, "path")
        val oldString = requireString(input, "old_string")
        val newString = requireString(input, "new_string")
        val replaceAll = optBool(input, "replace_all", false)

        val resolved = resolveProjectPath(project, path)
        if (!Files.exists(resolved)) return ToolResult.error("File not found: $path")
        if (!Files.isRegularFile(resolved)) return ToolResult.error("Not a regular file: $path")

        val current = Files.readString(resolved, StandardCharsets.UTF_8)
        val occurrences = current.split(oldString).size - 1
        if (occurrences == 0) {
            return ToolResult.error(
                "old_string was not found in $path. Re-read the file and try again with text " +
                    "that matches exactly (whitespace included)."
            )
        }
        if (occurrences > 1 && !replaceAll) {
            return ToolResult.error(
                "old_string occurs $occurrences times in $path. Pass replace_all: true or include " +
                    "more surrounding context to make it unique."
            )
        }
        val proposed = if (replaceAll) current.replace(oldString, newString)
                       else current.replaceFirst(oldString, newString)

        if (proposed == current) {
            return ToolResult.ok("No change — new_string is identical to old_string at $path.")
        }

        val decision = confirmer.confirm(
            WriteRequest(
                displayPath = path,
                currentContent = current,
                proposedContent = proposed,
                isNewFile = false,
                verb = "edit"
            )
        )
        if (decision == WriteConfirmer.Decision.REJECT) {
            return ToolResult.error("User rejected the edit to $path.")
        }
        writeViaVfs(project, resolved, proposed)
        val n = if (replaceAll) occurrences else 1
        return ToolResult.ok("Applied edit to $path ($n replacement${if (n == 1) "" else "s"}).")
    }

    // ---- write_file ---------------------------------------------------------

    private fun writeFile(project: Project, input: JsonObject, confirmer: WriteConfirmer): ToolResult {
        val path = requireString(input, "path")
        val content = requireString(input, "content")
        val resolved = resolveProjectPath(project, path)

        val isNew = !Files.exists(resolved)
        val current = if (isNew) "" else Files.readString(resolved, StandardCharsets.UTF_8)
        if (!isNew && current == content) {
            return ToolResult.ok("No change — proposed content is identical to $path.")
        }

        val decision = confirmer.confirm(
            WriteRequest(
                displayPath = path,
                currentContent = current,
                proposedContent = content,
                isNewFile = isNew,
                verb = if (isNew) "create" else "overwrite"
            )
        )
        if (decision == WriteConfirmer.Decision.REJECT) {
            return ToolResult.error("User rejected the write to $path.")
        }
        writeViaVfs(project, resolved, content)
        return ToolResult.ok(if (isNew) "Created $path." else "Overwrote $path.")
    }

    // ---- VFS helpers --------------------------------------------------------

    /**
     * Write the file through the IntelliJ VFS / WriteCommandAction so the
     * change appears in open editors and lands in the project's undo stack.
     */
    private fun writeViaVfs(project: Project, path: Path, content: String) {
        val errorHolder = arrayOf<Throwable?>(null)
        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteCommandAction.runWriteCommandAction(project, "Apply Claude Edit", null, {
                    val ioFile = path.toFile()
                    ioFile.parentFile?.mkdirs()
                    val lfs = LocalFileSystem.getInstance()
                    val vf = if (ioFile.exists()) {
                        lfs.refreshAndFindFileByIoFile(ioFile)
                            ?: throw IllegalStateException("Couldn't get VirtualFile for $path")
                    } else {
                        val parentVf = lfs.refreshAndFindFileByIoFile(ioFile.parentFile)
                            ?: throw IllegalStateException("Couldn't get parent dir for $path")
                        parentVf.createChildData(this, ioFile.name)
                    }
                    VfsUtil.saveText(vf, content)
                })
            } catch (t: Throwable) {
                errorHolder[0] = t
            }
        }
        errorHolder[0]?.let { throw it }
    }

    // ---- Path safety --------------------------------------------------------

    private fun projectBase(project: Project): Path {
        val base = project.basePath
            ?: throw IllegalStateException("Project has no base path; agent tools require an open project.")
        return Paths.get(base).toAbsolutePath().normalize()
    }

    private fun resolveProjectPath(project: Project, raw: String): Path {
        val base = projectBase(project)
        val candidate = Paths.get(raw)
        val resolved = if (candidate.isAbsolute) candidate.toAbsolutePath().normalize()
                       else base.resolve(candidate).toAbsolutePath().normalize()
        if (!resolved.startsWith(base)) {
            throw IllegalArgumentException("Path '$raw' is outside the project root.")
        }
        return resolved
    }

    // ---- JSON helpers -------------------------------------------------------

    private fun requireString(obj: JsonObject, key: String): String {
        val v = obj.get(key)
        if (v == null || v.isJsonNull) throw IllegalArgumentException("Missing required parameter: $key")
        return v.asString
    }

    private fun optBool(obj: JsonObject, key: String, default: Boolean): Boolean {
        val v = obj.get(key)
        if (v == null || v.isJsonNull) return default
        return v.asBoolean
    }
}
