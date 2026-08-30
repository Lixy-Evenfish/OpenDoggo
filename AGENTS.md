# OpenDoggo Agent Instructions

## Project Boundary

- This is a Java 17/Maven coding-agent harness; production code is under `src/main/java`, while `references/` is staged Python reference material (currently the s04 hooks lesson; s02/s03 code survives inside `references/code.py` under the "From s02-s03" section, and `references/images/` now holds `hooks-overview*.svg` — the permission diagrams were deleted).
- Implement model interaction, history, tool dispatch, permissions, local execution, termination, and errors in this repository. The assignment forbids agent frameworks/SDKs and API-hosted execution or file tools.
- s02 is complete: `Main` registers all five tools (`bash`, `read_file`, `write_file`, `edit_file`, `glob`). s03 is complete: the three-gate permission pipeline lives in `io.opendoggo.permission`; tools carry no policy. s04 (hooks) is staged in `references/` but not yet implemented in Java.

## Current Architecture

- `Main` loads configuration, wires dependencies (including `PermissionChecker` with a console `ApprovalPrompt`), owns the REPL, and rolls history back when a turn fails.
- `AgentLoop` appends the complete assistant content before executing tool calls, executes calls in response order, then appends all results as one user message. Preserve this protocol and each `tool_use_id`. Per call it prints a `> toolName` banner plus a 200-character console preview; full output still goes to the model. Before each execution it calls `permissionChecker.check(toolCall)`; a denial appends `ToolResult(toolCall.id(), "Permission denied.")` and skips execution.
- `permission.PermissionChecker` is the three-gate pipeline called by `AgentLoop` before dispatch: Gate 1 hard deny list (7 substrings, `bash` only, denied without asking), Gate 2 rules (file tools whose path escapes the workspace; `bash` destructive regex/keywords; `bash` referencing outside-workspace paths — `..` segments, `~`, absolute paths outside the workspace, teaching-grade token heuristic), Gate 3 user approval via the `ApprovalPrompt` callback (default deny). Tools without registered rules pass through — fail-open, matching the reference.
- `tool.impl` file tools (`ReadFileTool`, `WriteFileTool`, `EditFileTool`) resolve paths through package-private `WorkspacePaths.resolveAny`, which resolves relative to the workspace without containment; outside-workspace access is a Gate 2 rule the user may approve.
- `tool.ToolDispatch` is the name-to-`ToolHandler` registry and renders the `tools` array via `toolDefinitions()`. Adding a tool = implement `ToolHandler` and register it in `Main`; no schema edits anywhere else.
- `tool.impl.ShellTool` runs commands in the startup directory with a 120-second timeout and 50,000-character output limit. It carries no policy; dangerous-command checks live in the permission layer.
- `model.impl.AnthropicClient` directly implements the Anthropic Messages HTTP/JSON boundary. `README.md` documents the tools and current architecture tree.

## s03 — Permission Pipeline (implemented)

- Three gates sit between the model's `tool_use` and dispatch; the loop only gained a `check()` call per execution, and the tool set and dispatch are unchanged from s02.
- Gate 1 — hard deny list (`rm -rf /`, `sudo`, `shutdown`, `reboot`, `mkfs`, `dd if=`, `> /dev/sda`): substring match, `bash` only, denied immediately without asking.
- Gate 2 — permission rules: file tools whose resolved path escapes the workspace, or `bash` matching a destructive-command regex (`rm`/`del` as standalone command words, so `model` or `echo del x` do not match) or the keywords `rm `, `> /etc/`, `chmod 777`, or `bash` referencing outside-workspace paths (`..` segments, `~`, absolute paths outside the workspace — teaching-grade token heuristic). A hit escalates to Gate 3.
- Gate 3 — user approval via `ApprovalPrompt`: print the reason and the tool call, prompt `Allow? [y/N]`, default deny. The console implementation lives in `Main`; `AgentLoop` and the permission layer stay UI-free apart from the Gate 1 block message.
- Denied calls return a `tool_result` carrying the original `tool_use_id` with content `Permission denied.`
- Containment moved out of the tools: `WorkspacePaths` only resolves paths (`resolveAny`); outside-workspace access is a Gate 2 rule the user may approve. `ShellTool`'s s02 deny list was removed — policy lives only in `PermissionChecker`.
- Unregistered tools (e.g. `glob`) pass all gates — fail-open, matching the reference.
- The system prompt states "All destructive operations require user approval."
- s04 moves this hardcoded `check()` call onto a `PreToolUse` hook; see the s04 section below.
- Executable source of truth: `references/code.py`; lesson rationale: `references/README.zh.md`.

## s04 — Hooks (staged in `references/`, not yet implemented)

- Thesis: extensions hang on the loop instead of being written into it — the loop is a stable core that only calls `trigger_hooks()`, while permissions, logging, and summaries become registered callbacks (lesson title: "挂在循环上，不写进循环里").
- Registry (`references/code.py` lines 126–208): `HOOKS` maps event name → ordered callback list; `register_hook(event, callback)` appends; `trigger_hooks(event, *args)` runs callbacks in registration order and returns the first non-`None` result (short-circuiting the rest), else `None`. There is no JSON decision protocol — the return value alone is the decision.
- Four events and their semantics:
  - `UserPromptSubmit(query)` — fires in the REPL right after input, before the query enters history and the LLM. Return value ignored (fire-and-forget; "context injection" is described but only logging is demonstrated).
  - `PreToolUse(block)` — per tool call, replaces s03's hardcoded `check_permission()`. A non-`None` string blocks that call: the string becomes the `tool_result` content for the original `tool_use_id`, execution is skipped, and sibling calls in the same response still run.
  - `PostToolUse(block, output)` — after execution, before the result is appended. Return value ignored.
  - `Stop(messages)` — when the response has no tool calls, before the loop returns. A non-`None` string is appended as a user message and the loop continues instead of exiting.
- Five reference hooks: `permission_hook` (the s03 three-gate logic moved wholesale onto PreToolUse; rules unchanged), `log_hook` (gray `[HOOK] name(args)` line), `large_output_hook` (warns when output exceeds 100,000 chars), `context_inject_hook` (prints the working dir), `summary_hook` (counts `tool_result` blocks across history at Stop). Registration order matters: `permission_hook` is registered before `log_hook`, so a denied call short-circuits before the log line.
- Scope: no new tools, no hook configuration files (registration is imperative, in code), synchronous in-process callbacks, no error handling around hook calls.
- Reference-vs-Java deltas to preserve when porting: the reference `DENY_LIST` has 6 entries (Java's Gate 1 has 7 — Java added `> /dev/sda`); the reference permission hook lacks Java's bash outside-workspace rule — keep Java's stricter Gate 2; the reference prompts on the console inside the hook, but keep Java's `ApprovalPrompt` abstraction; the reference returns specific denial strings ("Permission denied by deny list" / "Permission denied by user") that become the `tool_result` content, unlike Java's generic "Permission denied.".
- Java integration points: `Stop` wraps `AgentLoop`'s empty-toolCalls branch; `PreToolUse` replaces the `permissionChecker.check(toolCall)` call before dispatch; `PostToolUse` sits between `toolDispatch.execute` and appending the result; `UserPromptSubmit` belongs in `Main`'s REPL before the query is appended to history.

## Commands

- Compile after source changes: `mvn compile`.
- Run tests: `mvn test`. There are currently no files under `src/test`.
- Build the executable shaded JAR: `mvn package`; run it with `java -jar target/opendoggo-0.1.0-SNAPSHOT.jar`.
- For development, generate `cp.txt` once with `mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -Dmdep.outputFile=cp.txt`, then run `java -cp "target/classes:$(cat cp.txt)" io.opendoggo.Main`.

## Configuration and Safety

- `.env` values override process environment values. Keep `ANTHROPIC_API_KEY` only in the process environment; never read, print, edit, or commit credentials.
- The startup directory is the agent workspace, and shell children inherit environment variables. Deny-list and permission-rule checks are teaching-grade substring matching, not a security boundary.
- Do not edit generated/local files: `target/`, `cp.txt`, or `dependency-reduced-pom.xml`.
