# OpenDoggo Agent Notes

## TUI Contract

- Keep the TUI in `ui.TerminalTui` very small; `image/迎宾页面.png` and `image/对话页面.png` are visual references only.
- The welcome screen needs only a title and one input box; the first submitted prompt opens the conversation screen.
- The conversation screen needs only scrollable chat history and a fixed input box. Omit the reference UI's model/mode selectors, token and cost status, agent tabs, tips, and other metadata. One deliberate exception: the `/` command palette, limited to real slash commands (`/init` only); do not grow it into a general menu.
- Keep the current workspace footer and per-turn elapsed time beside the `Doggo` label; do not grow them into a general status bar. Timing has two clocks: the input box shows a segment timer `Thinking... <elapsed>` that restarts from zero on every tool result, while the cumulative turn time rides the `Doggo Tool: <name>  <total>` labels and freezes into the final `Doggo  <elapsed>` label — no new status elements.
- Keep both the dependency choice and new code minimal. Do not remove the destructive-operation approval flow to simplify the UI.
- `TerminalTui` is the sole terminal-input owner and also implements `ApprovalPrompt`; do not add another reader for `System.in`.
- Planned follow-up work enriches the TUI content; route additions through the threading and channel rules in the map below instead of adding new terminal layers.

## TUI Implementation Map

All TUI behavior lives in `ui.TerminalTui` (Lanterna 3.1.2 `Screen`); read it before changing anything visual.

- One UI thread runs a 30 ms loop in `run()`: resize check → drain `pendingChat` → consume `completedTurn` → `pollInput()` → `render()` when `dirty` or when `timerFrameDue()` fires. Rendering is a full `screen.clear()` redraw per frame; there are no partial updates.
- Each Enter spawns a daemon `agent-turn` thread running the `TurnHandler` that `Main` wires to `runTurn` → `AgentLoop.run`. Enter stamps two clocks: `turnStartedNanos` (whole turn) and `segmentStartedNanos` (current wait). While `busy`, text editing is locked, the input shows `Thinking... <elapsed>` counting from `0.0s` — or `Running <command>... <elapsed>` on slash-command turns — and scrolling (PageUp/PageDown page, arrows line, tracked as `scrollFromBottom`) stays live. Each `showToolResult` stamps its label with the cumulative turn elapsed (`Doggo Tool: <name>  <total>`, agent thread, hence both stamps are volatile) and restarts `segmentStartedNanos`, so the input timer measures the wait since the last tool result; forced redraws every 100 ms come from `timerFrameDue()`, keyed on `turnStartedNanos`. `consumeCompletedTurn()` clears both stamps; the frozen total lands in the `Doggo  <elapsed>` label.
- Agent→UI channels are exactly two: the PostToolUse hook calls `showToolResult` into the `ConcurrentLinkedQueue pendingChat`, and the final text lands in `volatile completedTurn`. The UI thread never calls the agent directly.
- `ask()` (the `ApprovalPrompt` implementation) blocks the agent thread on a `CountDownLatch` while the UI draws a centered overlay: `y` allows, `n`/Enter denies. Escape/Ctrl+C/EOF quit from any state; shutdown denies pending approvals and interrupts the worker.
- The chat model is `List<ChatEntry>` (role, text); `buildDisplayLines` flattens entries into label/body lines after `sanitize` (strips control chars) and `wrap` (codepoint-safe, `TerminalTextUtils.getColumnWidth`, so CJK width is handled). Colors: `YOU` yellow; other labels (`Doggo`, `Doggo Tool: <name>  <cumulative elapsed>`, `Error`) deep purple `LABEL_COLOR`; welcome title dark gray; input-box edge bars and approval-box border/title black `EDGE_COLOR`.
- Layout constants: `INPUT_HEIGHT` 3, `FOOTER_HEIGHT` 1, minimum terminal 30×10 (below that only `Terminal too small` renders). The footer is the workspace path; the welcome screen is a centered title plus the same input box.
- Slash commands: `TerminalTui.COMMANDS` drives the palette (shown above the input only when the idle conversation-screen input starts with `/`, filtered by prefix) and the trigger feedback — `commandToken()` marks a submitted turn as a known command, and the busy input shows `Running <command>... <elapsed>` instead of `Thinking...`. Dispatch happens in `Main.handleInput` before any model call — `/init` rewrites to `INIT_PROMPT` and runs a normal turn, unknown commands return an error string without a model call (the TUI deliberately does not mark unknown slash input as running). Adding a command means a `COMMANDS` entry plus a `handleInput` branch; `AgentLoop` never sees raw slash text.
- Known deliberate gaps, likely targets for the planned enrichment: plain text only (no markdown), no streaming (answers appear at turn end), single-line input, tool previews capped at 200 chars, no way to cancel a running turn without quitting.

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
