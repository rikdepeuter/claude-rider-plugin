package com.rixit.claude.agent

import com.rixit.claude.api.ToolSchema

/**
 * The tools the model can call when agent mode is on.
 *
 * Keep this set small and well-described — Claude reads the descriptions to
 * pick the right tool. The plugin executes each tool itself; writes are gated
 * by [com.rixit.claude.agent.WriteConfirmer].
 */
object AgentTools {

    val READ_FILE = ToolSchema(
        name = "read_file",
        description = """
            Read the contents of a file in the project. The result includes
            line numbers (e.g. "  42\tcode here") so subsequent edit_file
            calls can reference exact text. Paths may be relative to the
            project root or absolute, but absolute paths must be inside the
            project. Large files are truncated.
        """.trimIndent(),
        inputSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the file, relative to the project root or absolute (inside the project)."
                )
            ),
            "required" to listOf("path")
        )
    )

    val LIST_FILES = ToolSchema(
        name = "list_files",
        description = """
            List files and subdirectories under a directory. Use this to
            explore the project layout before reading or editing. Returns
            one entry per line, with a trailing slash on directories.
        """.trimIndent(),
        inputSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "directory" to mapOf(
                    "type" to "string",
                    "description" to "Directory to list, relative to project root. Use '.' for the project root."
                ),
                "recursive" to mapOf(
                    "type" to "boolean",
                    "description" to "If true, walk subdirectories. Default false."
                )
            ),
            "required" to listOf("directory")
        )
    )

    val EDIT_FILE = ToolSchema(
        name = "edit_file",
        description = """
            Replace text in an existing file. The old_string must match
            exactly (including whitespace) and must be unique in the file
            unless replace_all is true. The user is shown a diff and must
            approve before the change is applied.
        """.trimIndent(),
        inputSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "File path."
                ),
                "old_string" to mapOf(
                    "type" to "string",
                    "description" to "Exact text to find."
                ),
                "new_string" to mapOf(
                    "type" to "string",
                    "description" to "Text to substitute. Use an empty string to delete."
                ),
                "replace_all" to mapOf(
                    "type" to "boolean",
                    "description" to "If true, replace every occurrence; otherwise old_string must be unique."
                )
            ),
            "required" to listOf("path", "old_string", "new_string")
        )
    )

    val WRITE_FILE = ToolSchema(
        name = "write_file",
        description = """
            Create a new file or overwrite an existing one with the given
            content. The user is shown a diff (or the full content, for new
            files) and must approve before the write is applied. Use
            edit_file for in-place changes whenever possible — write_file
            replaces the entire file.
        """.trimIndent(),
        inputSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "File path."
                ),
                "content" to mapOf(
                    "type" to "string",
                    "description" to "Full new contents of the file."
                )
            ),
            "required" to listOf("path", "content")
        )
    )

    val ALL: List<ToolSchema> = listOf(READ_FILE, LIST_FILES, EDIT_FILE, WRITE_FILE)
}
