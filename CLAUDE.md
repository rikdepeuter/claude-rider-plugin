# Working agreement

Rules I (Claude) follow when working on this repository. These exist so my
changes stay predictable and easy to roll back.

## Commits

After every change I make, I create one git commit and push it directly to
`main`. No branches, no pull requests — that's the current workflow.

Commit messages use **Conventional Commits** prefixes:

- `feat:` — new feature or user-visible behavior change.
- `fix:` — bug fix.
- `chore:` — tooling, build config, dependency bumps.
- `docs:` — documentation only.
- `refactor:` — internal change with no behavior delta.

Style: imperative, lowercase summary (`feat: add agent mode`), optional body
paragraph if the *why* isn't obvious from the diff.

If one user request produces several logically distinct changes, I prefer
multiple small commits over one large one.

## Version bumps

After every change, I bump `version = "..."` in `build.gradle.kts` using
SemVer. **Major stays at 0** until the project is explicitly cut to 1.0.

- **Patch** (`0.x.y` → `0.x.(y+1)`): bug fixes, refactors, doc/tooling
  changes — anything that doesn't visibly alter plugin behavior.
- **Minor** (`0.x.y` → `0.(x+1).0`): new features or user-visible
  behavior changes.

Every bump also gets a matching entry in `<change-notes>` inside
`src/main/resources/META-INF/plugin.xml` so the Rider plugin manager shows
what changed.

## Preserve user state across renames

Some identifiers carry persisted user data and must **not** be renamed
without a migration:

- `@State(name = "ClaudeChatSettings")` and `Storage("ClaudeChat.xml")` —
  the user's settings.
- `CredentialAttributes("ClaudeChatForRider", "anthropic-api-key")` — the
  saved API key in PasswordSafe.
- The plugin ID `com.rixit.claude.chat` — changing it makes JetBrains
  treat the next install as a separate plugin and forfeits everything
  above.

The user-visible name ("Claude AI Assistant"), vendor ("Rix IT Solutions"),
display strings, tool window title, etc. can all be changed freely.

## File encoding

PowerShell scripts (`*.ps1`) must be:

- saved with a UTF-8 BOM (`EF BB BF` at byte 0),
- terminated with CRLF (`\r\n`) line endings,
- ASCII-only (no em-dashes `—`, curly quotes, ellipses — PS 5.1 misparses
  them and falls back to Windows-1252 in confusing ways).

After editing a `.ps1` I re-normalize via:

```bash
sed -i '1s/^\xef\xbb\xbf//' file.ps1
sed -i 's/\r$//' file.ps1
(printf '\xef\xbb\xbf' && sed 's/$/\r/' file.ps1) > /tmp/x && cp /tmp/x file.ps1
```

Kotlin and XML sources don't need a BOM, but I avoid non-ASCII characters
in string literals that might end up in error messages or logs.

## Builds and verification

- `build.bat` → one-shot build, drops `plugin.zip` next to itself.
- `auto-build.bat` → continuous watcher; refreshes `plugin.zip` on every
  source save.

I can't execute Windows build scripts from my sandbox (Linux container,
no PowerShell, no outbound access to gradle.org / cache-redirector.jetbrains.com).
For verification I either ask the user to run `build.bat` or rely on the
fact that `auto-build.bat` is already running. After a change I check that
the source still references no obviously broken symbols (grep / Read), but
I don't get to actually compile.

## Agent-mode safety

When I'm operating inside the running plugin via agent mode (not while
editing this repo, but while a user has the plugin live):

- Tools never delete files, never run shell commands, never write outside
  `project.basePath`.
- Every write goes through `WriteConfirmer`. Auto-approve is opt-in per
  chat session via the confirmation dialog.
- `read_file` truncates at 100 KB so we don't blow the token budget on
  big files. `list_files` truncates at 500 entries for the same reason.
