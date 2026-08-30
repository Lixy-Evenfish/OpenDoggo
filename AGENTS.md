# OpenDoggo Agent Instructions

## Project Boundary

- This is a Java 17/Maven coding-agent harness; production code is under `src/main/java`, while `references/` is staged Python reference material (currently the s03 permission lesson; it replaced the former `references/s02_tool_use/` directory, and s02 code survives inside `references/code.py` under "From s02" sections).
- Implement model interaction, history, tool dispatch, permissions, local execution, termination, and errors in this repository. The assignment forbids agent frameworks/SDKs and API-hosted execution or file tools.
- s02 is complete: `Main` registers all five tools (`bash`, `read_file`, `write_file`, `edit_file`, `glob`). s03 (permission pipeline) is not started yet; the deny list still lives inside `ShellTool`.

## Current Architecture

- `Main` loads configuration, wires dependencies, owns the REPL, and rolls history back when a turn fails.
- `AgentLoop` appends the complete assistant content before executing tool calls, executes calls in response order, then appends all results as one user message. Preserve this protocol and each `tool_use_id`. Per call it prints a `> toolName` banner plus a 200-character console preview; full output still goes to the model.
- `tool.impl` file tools (`ReadFileTool`, `WriteFileTool`, `EditFileTool`) resolve paths through package-private `WorkspacePaths`, which rejects paths escaping the workspace.
- `tool.ToolDispatch` is the name-to-`ToolHandler` registry and renders the `tools` array via `toolDefinitions()`. Adding a tool = implement `ToolHandler` and register it in `Main`; no schema edits anywhere else.
- `tool.impl.ShellTool` runs commands in the startup directory with a 120-second timeout and 50,000-character output limit. It still owns an internal 5-entry deny list — s03 extracts this into the permission layer.
- `model.impl.AnthropicClient` directly implements the Anthropic Messages HTTP/JSON boundary. `README.md` documents the tools and current architecture tree.

## s02 to s03 — What s03 Adds

- s02 recap: five tools (`bash`, `read_file`, `write_file`, `edit_file`, `glob`) plus a name-to-handler registry; the agent loop itself is unchanged from s01.
- s03 adds exactly one thing: a three-gate permission pipeline inserted between the model's `tool_use` and dispatch. The loop only gains a `check_permission()` call before each execution; the tool set and dispatch stay as-is.
- Gate 1 — hard deny list (`rm -rf /`, `sudo`, `shutdown`, `reboot`, `mkfs`, `dd if=`, `> /dev/sda`): substring match, applied to `bash` only, denied immediately without asking.
- Gate 2 — permission rules: file tools whose resolved path escapes the workspace, or `bash` matching a destructive-command regex (`rm`/`del` as standalone command words, so `model` or `echo del x` do not match) or the keywords `rm `, `> /etc/`, `chmod 777`. A hit does not deny by itself; it escalates to Gate 3.
- Gate 3 — user approval: print the reason and the tool call, prompt `Allow? [y/N]`, default deny.
- Denied calls still return a `tool_result` carrying the original `tool_use_id` with content `Permission denied.`, so the message protocol is unchanged.
- Containment moves out of the tools: in s02, file tools rejected outside-workspace paths themselves; in s03 that condition becomes a rule the user may approve.
- The system prompt gains "All destructive operations require user approval."

## Java Upgrade Path to s03

1. (Done) s02 file tools exist and all five tools are registered in `Main`; file tools enforce workspace containment via `WorkspacePaths` — when implementing s03, move that containment check into the Gate 2 rules so the user can approve it.
2. Extract `ShellTool`'s internal deny list into a permission layer (e.g. `io.opendoggo.permission.PermissionChecker`) called by `AgentLoop` before `toolDispatch.execute(toolCall)`, and expand the list to s03's 7 entries. Tools themselves carry no policy.
3. Gate 3 needs the console: inject an approval callback (an interface supplied by `Main`'s REPL) into the permission checker so `AgentLoop` stays free of UI.
4. On denial, append `ToolResult(toolCall.id(), "Permission denied.")` — same shape as a normal result, so the loop protocol holds.
5. Update the system prompt in `Main` to state that destructive operations require user approval.
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
