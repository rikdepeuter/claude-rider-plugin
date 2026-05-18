# Claude Chat for Rider

A small JetBrains plugin that drops a Claude chat tool window into Rider (or any
IntelliJ-platform IDE) and lets you push the current file or selection in as
context.

## What you get

- **Tool window** docked on the right titled "Claude". Open with **Ctrl+Alt+K**.
- **Send Current File to Claude** — `Ctrl+Alt+Shift+F` (also in the editor right-click menu).
- **Send Selection to Claude** — `Ctrl+Alt+Shift+S` (also in the editor right-click menu).
- **Auto-attached header** on every message: file path, language, and cursor `Lline:col`,
  so Claude always knows where you are.
- **Settings page** at *Settings → Tools → Claude Chat* for the API key, model,
  base URL, max tokens, and system prompt.
- API key stored in IDE PasswordSafe (OS keychain when available), never in plain XML.

## Requirements

- JDK 21 (the IntelliJ Platform 2.x build uses Java 21).
- Rider 2024.3 or newer (you can change the target in `build.gradle.kts`).
- An Anthropic API key.

## Build and install

On Windows, double-click `build.bat` (or run it from a terminal). The first
run will:

1. Check that Java 21+ is on the PATH.
2. Find Gradle — using the wrapper if present, the system `gradle` if installed,
   or downloading Gradle 8.10 once (cached under `%LOCALAPPDATA%\claude-chat-rider`).
3. Generate the Gradle wrapper so future builds skip step 2.
4. Run `gradlew buildPlugin`.
5. Copy the produced archive to `plugin.zip` next to the script.

Then in Rider: *Settings → Plugins → ⚙ → Install Plugin from Disk…* and pick
`plugin.zip`. Restart when prompted.

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

1. Open *Settings → Tools → Claude Chat*.
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

## What it doesn't do (yet)

- **No tool use / no function calling.** Plain text chat.
- **No conversation history across IDE restarts.** Clear by design — clears
  every time the panel is recreated.
- **No request cancellation.** Once a stream starts there's no Stop button;
  it runs to completion or fails.
- **Tiny markdown renderer.** Only fenced code blocks and inline `code` are
  styled; everything else is plain text. During streaming the in-flight bubble
  is rendered as plain escaped text (so half-open code fences don't break the
  regex); markdown styling is applied once the response completes.

These are the obvious next steps if you want to extend it.

## Streaming

Replies stream via Anthropic's SSE endpoint. The chat panel shows a growing
"Claude:" bubble with a cursor (`▌`) while text arrives. If the request fails
mid-stream the last user turn is dropped from history so you can edit and
retry.
