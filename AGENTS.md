# OpenDoggo Agent Notes

## TUI Contract

- Keep the TUI in `ui.TerminalTui` very small; `image/迎宾页面.png` and `image/对话页面.png` are visual references only.
- The welcome screen needs only a title and one input box; the first submitted prompt opens the conversation screen.
- The conversation screen needs only scrollable chat history and a fixed input box. Omit the reference UI's model/mode selectors, token and cost status, command palette, agent tabs, tips, and other metadata.
- Keep the current workspace footer and per-turn elapsed time beside the `Doggo` label; do not grow them into a general status bar.
- Keep both the dependency choice and new code minimal. Do not remove the destructive-operation approval flow to simplify the UI.
- `TerminalTui` is the sole terminal-input owner and also implements `ApprovalPrompt`; do not add another reader for `System.in`.

## Repository Shape

- This is one Java 17 Maven module with no Maven wrapper. Runtime code is under `src/main/java/io/opendoggo`; there is currently no `src/test` tree.
- `Main` is the composition root and connects `TerminalTui` to the agent. `AgentLoop` is the UI-free protocol core; keep terminal rendering and input out of it.
- Do not write to stdout/stderr while the full-screen TUI is active. Tool outcomes go through `PostToolUse` to `TerminalTui` as a name plus a maximum 200-character preview; other diagnostics stay hidden because direct output corrupts redraws.
- `references/` is the standalone Python s11 Background Tasks lesson, not a cumulative implementation. The Java runtime is cumulative (s02-s08, s11, s14, plus the custom Docker sandbox); do not replace newer Java behavior with the lesson listing.
- Root `skills/*/SKILL.md` files are runtime data scanned once at startup. `references/` is teaching material and is not loaded by the application.

## Runtime Invariants

- Preserve Anthropic message pairing: append the complete assistant response before tools, keep every `tool_use_id`, and append all sibling `tool_result` blocks in one user message. A denied or failed call still needs its paired result.
- Permission checking is the first `PreToolUse` hook. File access outside the startup directory, risky shell commands, and unapproved `mcp__*` tools require approval; the deny-list blocks without prompting. These checks are teaching-grade policy, not a sandbox.
- The startup directory is the agent workspace. `.env` overrides process environment values; keep `ANTHROPIC_API_KEY` out of `.env` and source control. `ANTHROPIC_BASE_URL` must omit `/v1/messages` because `AnthropicClient` appends it.
- Parent tools are the five base tools plus `todo_write`, `task`, `load_skill`, `compact`, `connect_mcp`, and `run_code`. Subagents intentionally receive only the five base tools and share the parent's permission checker, compactor, background manager, and filesystem.
- `compact` is definition-only in `ToolDispatch`: `AgentLoop` bypasses `PreToolUse`/dispatch, emits its `PostToolUse` trace, and compacts only after the whole tool-result batch is recorded.
- Background execution happens only when a `bash` call explicitly sets boolean `run_in_background: true`. Completion does not wake the loop; it is collected before a later model call.
- MCP tools are registered dynamically into the parent dispatch and refresh the parent client's tool definitions at connection time.
- Context compaction mutates message lists in place and writes recoverable artifacts under ignored `.transcripts/` and `.task_outputs/`. Sandbox staging is under ignored `.sandbox/`.
- Adding an ordinary tool means implementing `ToolHandler` and registering it in `Main`; tool schemas sent to the model come from `ToolDispatch.toolDefinitions()`. Do not add an agent SDK: the HTTP boundary intentionally uses JDK `HttpClient` plus Jackson.

## Commands

- Fast compile: `mvn compile`
- Build the executable shaded JAR: `mvn package`; run it with `java -jar target/opendoggo-0.1.0-SNAPSHOT.jar`.
- `mvn test` currently executes no repository tests. Verification is performed by API-free `io.opendoggo.demo.*` mains; run the demo covering the changed subsystem.
- Build a demo classpath once with `mvn dependency:build-classpath -Dmdep.outputFile=cp.txt`, then run, for example, `java -cp "target/classes:$(cat cp.txt)" io.opendoggo.demo.BackgroundDemo`. Useful API-free demos are `TodoDemo`, `SkillScanDemo`, `CompactionDemo`, `BackgroundDemo`, and `McpDemo`.
- `SandboxDemo` requires Docker and locally available images. `SummarizerSmoke` makes a real model request; do not run either as routine verification.
- Running the real TUI requires `ANTHROPIC_API_KEY` and `MODEL_ID`; avoid live API acceptance tests unless the user requests them.
