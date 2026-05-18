# Claude AI Assistant for Rider

A small JetBrains plugin that drops a Claude chat tool window into Rider (or any
IntelliJ-platform IDE) and lets you push the current file or selection in as
context.

## What you get

- **Tool window** docked on the right titled "Claude". Open with **Ctrl+Alt+K**.
- **Send Current File to Claude** — `Ctrl+Alt+Shift+F` (also in the editor right-click menu).
- **Send Selection to Claude** — `Ctrl+Alt+Shift+S` (also in the editor right-click menu).
- **Auto-attached header** on every message: file path, language, and cursor `Lline:col`,
  so Claude always knows where you are.
- **Settings page** at *Settings → Tools → Claude AI Assistant* for the API key, model,
  base URL, max tokens, and system prompt.
- API key stored in IDE PasswordSafe (OS keychain when available), never in plain XML.

## Requirements

- JDK 21 (the IntelliJ Platform 2.x build uses Java 21).
- Rider 2024.3 or newer (you can change the target in `build.gradle.kts`).
- An Anthropic API key.

## Build and install

### One-shot build

Run `build.bat` (double-click in Explorer or from a terminal). The first
run will:

1. Check that Java 21+ is on the PATH.
2. Find Gradle — using the wrapper if present, the system `gradle` if installed,
   or downloading Gradle 8.10 once (cached under `%LOCALAPPDATA%\claude-chat-rider`).
3. Generate the Gradle wrapper so future builds skip step 2.
4. Run `gradlew buildPlugin`.
5. Copy the produced archive to `plugin.zip` next to the script.

Then in Rider: *Settings → Plugins → ⚙ → Install Plugin from Disk…* and pick
`plugin.zip`. Restart when prompted.

### Auto-update from the local zip (one-time setup)

Each build also writes an `updatePlugins.xml` next to `plugin.zip`. Point
Rider at it once and from then on Rider will treat new versions of the
plugin like marketplace updates — checking on startup (and periodically),
offering or auto-applying them as you configure.

1. In Rider, open *Settings → Plugins → ⚙ (gear) → Manage Plugin Repositories…*.
2. Add this URL (adjust the path to match your checkout):

   ```
   file:///C:/Users/RikDePeuter/Source/claude-chat-rider-plugin/updatePlugins.xml
   ```

3. Click OK twice. Rider re-scans plugin repos.
4. Make sure *Settings → Appearance & Behavior → System Settings → Updates*
   has **Check IDE updates** + **Use the same settings for plugin updates**
   (or just plugin updates) enabled.

After that, leave `auto-build.bat` running. Every time I push a version
bump and the build refreshes `plugin.zip` + `updatePlugins.xml`, Rider
will see a newer version on its next check and prompt to install.

### Auto-build mode (recommended while iterating)

Run `auto-build.bat` once in a terminal and leave it open. It does the
first build and then watches the source tree — every save triggers a
rebuild and refreshes `plugin.zip` automatically. You just reinstall the
zip in Rider when you want to pick up the changes. Press Ctrl+C in the
watcher terminal to stop.

If you don't have JDK 21:
```
winget install EclipseAdoptium.Temurin.21.JDK
```
Gradle is **not** required on PATH — the script will fetch it.

If you'd rather drive Gradle manually:

```bash
gradle wrapper --gradle-version 8.10   # one-time
./gradlew buildPlugin                  # produces build/distributions/*.zip
./gradlew runIde                       # launches a sandbox Rider with the plugin
```

## First-time setup

1. Open *Settings → Tools → Claude AI Assistant*.
2. Paste your Anthropic API key.
3. Pick or type a model (defaults to `claude-sonnet-4-6`).
4. Click OK.
5. Open the Claude tool window (Ctrl+Alt+K) and say hi.

## File layout

```
claude-chat-rider/
├── build.gradle.kts                  Gradle build, targets Rider 2024.3
├── settings.gradle.kts
├── gradle.properties
└── src/main/
    ├── kotlin/com/rixit/claude/
    │   ├── actions/                  Three AnActions (open, send file, send selection)
    │   ├── api/ClaudeApiClient.kt    HTTP client for the Anthropic Messages API
    │   ├── context/EditorContext.kt  Snapshot of file/cursor/selection
    │   ├── settings/                 Persistent settings + UI page
    │   └── ui/                       Tool window factory + chat panel
    └── resources/META-INF/plugin.xml
```

## Agent mode

Each chat tab has an **Agent mode** checkbox. With it on, Claude can call tools:

- `read_file(path)` — read a file's content (with line numbers, capped at 100 KB).
- `list_files(directory, recursive?)` — explore the project tree.
- `edit_file(path, old_string, new_string, replace_all?)` — find-and-replace edit.
- `write_file(path, content)` — create a new file or overwrite an existing one.

**Every write is gated.** Before any `edit_file` or `write_file` actually
touches disk, the plugin pops a modal dialog with an embedded side-by-side
diff (current vs. proposed) and an *Apply / Reject* choice. Reads run inline,
no prompt.

**Auto-approve windows.** In the same dialog there's a dropdown:
*Just this change*, *5 minutes*, *30 minutes*, *1 hour*, *until I close this
chat*. Pick a window and subsequent writes from the agent loop apply without
re-prompting. A yellow banner at the top of the chat shows the remaining
time and a *Cancel* button to revoke immediately. Clearing the chat or
closing the tab also clears the timer.

**Path safety.** All paths resolve against `project.basePath`. Absolute paths
outside the project are rejected. There's no `delete_file`, no `run_command`
— this is an editor, not a shell.

**Mode caveats.** Agent mode is non-streaming (responses arrive in chunks
between tool calls). Chat mode (the 