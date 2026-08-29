# Anthropic Messages Format Quick Reference

This project uses the Anthropic Messages API. The examples below match the message flow in `references/s02_tool_use/code.py` and the Java JSON boundary in `model.impl.AnthropicClient`.

## Request

`system` is a top-level request field, not a message with role `system`. The `tools` array tells the model which tools exist.

```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 8000,
  "system": "You are a coding agent at /workspace. Use tools to solve tasks. Act, don't explain.",
  "messages": [
    {
      "role": "user",
      "content": "Read README.md"
    }
  ],
  "tools": [
    {
      "name": "read_file",
      "description": "Read file contents.",
      "input_schema": {
        "type": "object",
        "properties": {
          "path": { "type": "string" },
          "limit": { "type": "integer" }
        },
        "required": ["path"]
      }
    }
  ]
}
```

Read tool information as follows:

- Available tool kinds: `tools[*].name` in the request.
- Tool selected by the model: a response content block where `type` is `tool_use`.
- Selected tool and arguments: that block's `name` and `input`.

## Text Response

A representative complete API response is:

```json
{
  "id": "msg_01Example",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-4-6",
  "content": [
    {
      "type": "text",
      "text": "README.md describes OpenDoggo."
    }
  ],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": {
    "input_tokens": 100,
    "output_tokens": 20
  }
}
```

Do not append this entire response object to history. Append only its role and content:

```json
{
  "role": "assistant",
  "content": [
    {
      "type": "text",
      "text": "README.md describes OpenDoggo."
    }
  ]
}
```

## Tool-Use Response

The model requests a local tool through a `tool_use` content block. A response can contain text and one or more tool calls.

```json
{
  "id": "msg_02Example",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-4-6",
  "content": [
    {
      "type": "text",
      "text": "I will inspect the file."
    },
    {
      "type": "tool_use",
      "id": "toolu_01Example",
      "name": "read_file",
      "input": {
        "path": "README.md",
        "limit": 100
      }
    }
  ],
  "stop_reason": "tool_use",
  "stop_sequence": null,
  "usage": {
    "input_tokens": 120,
    "output_tokens": 35
  }
}
```

## Tool Result and Next Request

First append the assistant's complete `content` array to history. Then execute every `tool_use` in content order and return the results together in one user message:

```json
{
  "role": "user",
  "content": [
    {
      "type": "tool_result",
      "tool_use_id": "toolu_01Example",
      "content": "# OpenDoggo\n\nJava implementation of a minimal coding agent."
    }
  ]
}
```

The next API request replays the complete conversation:

```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 8000,
  "system": "You are a coding agent at /workspace. Use tools to solve tasks. Act, don't explain.",
  "messages": [
    {
      "role": "user",
      "content": "Read README.md"
    },
    {
      "role": "assistant",
      "content": [
        {
          "type": "text",
          "text": "I will inspect the file."
        },
        {
          "type": "tool_use",
          "id": "toolu_01Example",
          "name": "read_file",
          "input": {
            "path": "README.md",
            "limit": 100
          }
        }
      ]
    },
    {
      "role": "user",
      "content": [
        {
          "type": "tool_result",
          "tool_use_id": "toolu_01Example",
          "content": "# OpenDoggo\n\nJava implementation of a minimal coding agent."
        }
      ]
    }
  ],
  "tools": [
    {
      "name": "read_file",
      "description": "Read file contents.",
      "input_schema": {
        "type": "object",
        "properties": {
          "path": { "type": "string" },
          "limit": { "type": "integer" }
        },
        "required": ["path"]
      }
    }
  ]
}
```

`tool_result.tool_use_id` must exactly equal the corresponding `tool_use.id`. For multiple calls, preserve response order and put all result blocks in the same user message.

## Java Mapping

| Anthropic concept | Java location |
|---|---|
| Request construction and `tools` | `src/main/java/io/opendoggo/model/impl/AnthropicClient.java` |
| `text` / `tool_use` block | `src/main/java/io/opendoggo/model/ContentBlock.java` |
| User/assistant history entry | `src/main/java/io/opendoggo/model/Message.java` |
| Complete parsed assistant content | `src/main/java/io/opendoggo/model/ModelResponse.java` |
| `tool_result` block | `src/main/java/io/opendoggo/model/ToolResult.java` |
| Append/execute/return loop | `src/main/java/io/opendoggo/agent/AgentLoop.java` |

The current Java parser intentionally keeps only response `content` blocks of type `text` and `tool_use`; other response metadata is not stored in conversation history.

## Source Basis

- Tool definitions: `references/s02_tool_use/code.py:126-139`.
- Passing definitions to the model: `references/s02_tool_use/code.py:153-158`.
- Reading `tool_use`, dispatching it, and returning `tool_result`: `references/s02_tool_use/code.py:159-175`.
- Ordered multiple calls and unchanged loop: `references/s02_tool_use/README.zh.md:109-135`.
- Java request/response JSON boundary: `src/main/java/io/opendoggo/model/impl/AnthropicClient.java:125-216`.
- Java history and result serialization: `src/main/java/io/opendoggo/model/Message.java:37-70` and `src/main/java/io/opendoggo/model/ToolResult.java:28-39`.
- Official end-to-end tool-use example: <https://github.com/anthropics/anthropic-sdk-typescript/blob/main/examples/tools.ts>.
