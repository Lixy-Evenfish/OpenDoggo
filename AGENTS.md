# OpenDoggo Agent Instructions

## Project Boundary

- This is a Java 17/Maven coding-agent harness; production code is under `src/main/java`, while `references/` is staged Python reference material.
- Implement model interaction, history, tool dispatch, local execution, termination, and errors in this repository. The assignment forbids agent frameworks/SDKs and API-hosted execution or file tools.
- The current Java implementation corresponds to s01, despite the presence of the s02 reference.

## Current Architecture

- `Main` loads configuration, wires dependencies, owns the REPL, and rolls history back when a turn fails.
- `AgentLoop` appends the complete assistant content before executing tool calls, executes calls in response order, then appends all results as one user message. Preserve this protocol and each `tool_use_id`.
- `model.impl.AnthropicClient` directly implements the Anthropic Messages HTTP/JSON boundary. The architecture tree in `README.md` still shows its old package.
- `ShellTool` runs commands in the process startup directory with a 120-second timeout and 50,000-character output limit.
- Tool schemas sent by `AnthropicClient` and local dispatch handlers must be changed together.

## s01 to s02

- s01/current Java exposes only `bash` and hard-codes its dispatch.
- s02 adds `read_file`, `write_file`, `edit_file`, and `glob`, plus a name-to-handler registry; it does not redesign the agent loop.
- s02 file tools normalize paths and reject paths outside the startup workspace. This containment does not apply to `bash`.
- Use `references/s02_tool_use/code.py` as the executable source of truth and `references/s02_tool_use/README.zh.md` for the lesson rationale.

## Commands

- Compile after source changes: `mvn compile`.
- Run tests: `mvn test`. There are currently no files under `src/test`.
- Build the executable shaded JAR: `mvn package`; run it with `java -jar target/opendoggo-0.1.0-SNAPSHOT.jar`.
- For development, generate `cp.txt` once with `mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -Dmdep.outputFile=cp.txt`, then run `java -cp "target/classes:$(cat cp.txt)" io.opendoggo.Main`.

## Configuration and Safety

- `.env` values override process environment values. Keep `ANTHROPIC_API_KEY` only in the process environment; never read, print, edit, or commit credentials.
- The startup directory is the agent workspace, and shell children inherit environment variables. `ShellTool` uses only five substring checks, so treat it as teaching-grade filtering, not a security boundary.
- Do not edit generated/local files: `target/`, `cp.txt`, or `dependency-reduced-pom.xml`.
