# OpenDoggo

南大软院推免项目，开源 harness。

Java 实现的最小 AI coding agent，对应 [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 的 s01 环节。

## v1

当前版本实现了 agent loop 的核心闭环：

```
模型 -> 工具调用 -> 执行 -> 结果回灌 -> 模型 -> ... -> 无工具调用则结束
```

功能范围：

- **Agent Loop** — 模型请求工具就继续循环，不请求就返回最终回答，上限 50 轮
- **bash 工具** — 唯一工具，在启动目录执行 shell 命令，120s 超时，输出上限 50000 字符
- **多工具调用** — 单次模型回复中的多个 tool_use 会依次执行后一并回传
- **命令行 REPL** — 多轮对话，历史累积在内存中
- **Anthropic Messages API 客户端** — 仅用 JDK `HttpClient`，无 SDK 依赖
- **错误回滚** — 单轮失败时回滚历史，避免残留消息污染后续请求

尚未实现（对应后续环节）：权限控制、hooks、TodoWrite、子 agent、skill 加载、上下文压缩。

## 环境要求

JDK 17+、Maven。

## 配置

项目根目录建 `.env`：

```
ANTHROPIC_BASE_URL=https://opencode.ai/zen
MODEL_ID=claude-sonnet-4-6
```

API key 走环境变量，**不要写进 `.env`**：

```bash
read -rsp "key: " ANTHROPIC_API_KEY && export ANTHROPIC_API_KEY && echo
```

`.env` 的优先级高于环境变量（对齐 s1 的 `load_dotenv(override=True)`），所以 `.env` 里写了同名项会把环境变量顶掉。

`ANTHROPIC_BASE_URL` 只写到域名段，客户端会自行拼接 `/v1/messages`。不填则默认 `https://api.anthropic.com`。

### 可用模型

客户端只说 Anthropic Messages 协议。用 [OpenCode Zen](https://opencode.ai/docs/zen/) 时，仅 `/v1/messages` 端点的模型可用：

```
claude-opus-5      claude-sonnet-5     claude-haiku-4-5
claude-opus-4-5    claude-sonnet-4-6   claude-fable-5
qwen3.7-max        qwen3.5-plus        ...
```

`gpt-*`、`gemini-*`、`glm-*`、`kimi-*`、`deepseek-*` 走的是其他协议，不兼容。

`MODEL_ID` 填裸 id，不加 `opencode/` 前缀。

## 构建运行

### 方式一：打包成可执行 JAR

```bash
mvn package
java -jar target/opendoggo-0.1.0-SNAPSHOT.jar
```

## 结构

```
io.opendoggo
├── Main                    入口：读配置、装配、REPL
├── agent.AgentLoop         核心循环：调模型、分发工具、回灌结果
├── tool.ShellTool          bash 执行：超时控制、输出截断、进程树清理
└── model
    ├── ModelClient         模型客户端接口
    ├── AnthropicClient     Messages API 实现
    ├── Message             对话消息（role + JSON content）
    ├── ContentBlock        内容块（text / tool_use）
    ├── ModelResponse       模型回复，可提取工具调用与文本
    └── ToolResult          工具执行结果
```

## 注意

**启动目录即 agent 工作区。** bash 命令是真实执行的，具有写权限。`ShellTool` 的危险命令拦截仅为 `rm -rf /`、`sudo`、`shutdown`、`reboot`、`> /dev/` 五条子串匹配，属教学级防护，容易绕过。请勿在重要目录直接运行。

**子进程继承环境变量。** agent 执行的 shell 可读取 `ANTHROPIC_API_KEY`，存在 prompt injection 泄露风险。

## 许可

见 [LICENSE](LICENSE)。
