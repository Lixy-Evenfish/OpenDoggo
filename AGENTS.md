# OpenDoggo Agent Instructions

## Project Boundary

- This is a Java 17/Maven coding-agent harness; production code is under `src/main/java`, while `references/` is staged Python reference material (currently the s03 permission lesson; it replaced the former `references/s02_tool_use/` directory, and s02 code survives inside `references/code.py` under "From s02" sections).
- Implement model interaction, history, tool dispatch, permissions, local execution, termination, and errors in this repository. The assignment forbids agent frameworks/SDKs and API-hosted execution or file tools.
- s02 is complete: `Main` registers all five tools (`bash`, `read_file`, `write_file`, `edit_file`, `glob`). s03 is complete: the three-gate permission pipeline lives in `io.opendoggo.permission`; tools carry no policy.

## Current Architecture

- `Main` loads configuration, wires dependencies (including `PermissionChecker` with a console `ApprovalPrompt`), owns the REPL, and rolls history back when a turn fails.
- `AgentLoop` appends the complete assistant content before executing tool calls, executes calls in response order, then appends all results as one user message. Preserve this protocol and each `tool_use_id`. Per call it prints a `> toolName` banner plus a 200-character console preview; full output still goes to the model. Before each execution it calls `permissionChecker.check(toolCall)`; a denial appends `ToolResult(toolCall.id(), "Permission denied.")` and skips execution.
- `permission.PermissionChecker` is the three-gate pipeline called by `AgentLoop` before dispatch: Gate 1 hard deny list (7 substrings, `bash` only, denied without asking), Gate 2 rules (file tools whose path escapes the workspace; `bash` destructive regex/keywords), Gate 3 user approval via the `ApprovalPrompt` callback (default deny). Tools without registered rules pass through — fail-open, matching the reference.
- `tool.impl` file tools (`ReadFileTool`, `WriteFileTool`, `EditFileTool`) resolve paths through package-private `WorkspacePaths.resolveAny`, which resolves relative to the workspace without containment; outside-workspace access is a Gate 2 rule the user may approve.
- `tool.ToolDispatch` is the name-to-`ToolHandler` registry and renders the `tools` array via `toolDefinitions()`. Adding a tool = implement `ToolHandler` and register it in `Main`; no schema edits anywhere else.
- `tool.impl.ShellTool` runs commands in the startup directory with a 120-second timeout and 50,000-character output limit. It carries no policy; dangerous-command checks live in the permission layer.
- `model.impl.AnthropicClient` directly implements the Anthropic Messages HTTP/JSON boundary. `README.md` documents the tools and current architecture tree.

## s03 — Permission Pipeline (implemented)

- Three gates sit between the model's `tool_use` and dispatch; the loop only gained a `check()` call per execution, and the tool set and dispatch are unchanged from s02.
- Gate 1 — hard deny list (`rm -rf /`, `sudo`, `shutdown`, `reboot`, `mkfs`, `dd if=`, `> /dev/sda`): substring match, `bash` only, denied immediately without asking.
- Gate 2 — permission rules: file tools whose resolved path escapes the workspace, or `bash` matching a destructive-command regex (`rm`/`del` as standalone command words, so `model` or `echo del x` do not match) or the keywords `rm `, `> /etc/`, `chmod 777`. A hit escalates to Gate 3.
- Gate 3 — user approval via `ApprovalPrompt`: print the reason and the tool call, prompt `Allow? [y/N]`, default deny. The console implementation lives in `Main`; `AgentLoop` and the permission layer stay UI-free apart from the Gate 1 block message.
- Denied calls return a `tool_result` carrying the original `tool_use_id` with content `Permission denied.`
- Containment moved out of the tools: `WorkspacePaths` only resolves paths (`resolveAny`); outside-workspace access is a Gate 2 rule the user may approve. `ShellTool`'s s02 deny list was removed — policy lives only in `PermissionChecker`.
- Unregistered tools (e.g. `glob`) pass all gates — fail-open, matching the reference.
- The system prompt states "All destructive operations require user approval."
- Next lesson: s04 hooks — the `check()` call is hardcoded in the loop; hooks will externalize such extensions.
- Executable source of truth: `references/code.py`; lesson rationale: `references/README.zh.md`.

## Commands

- Compile after source changes: `mvn compile`.
- Run tests: `mvn test`. There are currently no files under `src/test`.
- Build the executable shaded JAR: `mvn package`; run it with `java -jar target/opendoggo-0.1.0-SNAPSHOT.jar`.
- For development, generate `cp.txt` once with `mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -Dmdep.outputFile=cp.txt`, then run `java -cp "target/classes:$(cat cp.txt)" io.opendoggo.Main`.

## Configuration and Safety

- `.env` values override process environment values. Keep `ANTHROPIC_API_KEY` only in the process environment; never read, print, edit, or commit credentials.
- The startup directory is the agent workspace, and shell children inherit environment variables. Deny-list and permission-rule checks are teaching-grade substring matching, not a security boundary.
- Do not edit generated/local files: `target/`, `cp.txt`, or `dependency-reduced-pom.xml`.
