# OpenDoggo

南大软院推免项目，开源 harness。

Java 实现的最小 AI coding agent：agent loop、多工具、权限管线、hooks。

## v1

当前版本实现了 agent loop 的核心闭环：

```
模型 -> 工具调用 -> 执行 -> 结果回灌 -> 模型 -> ... -> 无工具调用则结束
```

功能范围：

- **Agent Loop** — 核心循环支持 Anthropic Messages 格式的 AI 调用（仅 JDK `HttpClient`，无 SDK 依赖）：模型请求工具就继续循环，不请求就返回最终回答，上限 50 轮；单次回复中的多个 tool_use 依次执行后一并回传。外层是命令行 REPL，多轮对话，历史驻留内存
- **工具注册与调度** — 工具实现统一的 `ToolHandler` 接口、注册进 `ToolDispatch` 按名分发；发给模型的 `tools` 数组由注册表自动生成，新增工具不需要改客户端代码
- **五种具体工具** — `bash` / `read_file` / `write_file` / `edit_file` / `glob`，覆盖命令执行、读、写、精确编辑与文件查找（详见下文[工具](#工具)一节）
- **权限控制与错误回退** — 最基础的工具权限控制：执行前依次过硬拒绝表、规则匹配、用户审批三道闸门（详见下文[权限](#权限)一节）；单轮失败时回滚历史，避免残留消息污染后续请求

## v2（进行中）

v2 的主线是让 agent loop 成为稳定内核,并扩展其他刚需功能：扩展点以 hook 形式挂在循环外，新增能力只注册 hook，循环代码不再改动。

功能范围：

- **Hooks** — Agent loop 引入 hook 与 trigger 机制，在 4 个关键位置（`UserPromptSubmit` / `PreToolUse` / `PostToolUse` / `Stop`）设置挂载点，`AgentLoop` 的代码就此固定，后续扩展只需在 `Main` 注册 hook（权限、横幅、预览已挂上，示例 hook 补全中）
- **TodoWrite**— 计划工具，让 agent 显式维护任务清单
- **SubAgent**（尚未完成）— 子 agent，把独立子任务委派出去
- **Skill**（尚未完成）— 技能加载机制

## 环境要求

JDK 17+、Maven。

## 配置

项目根目录建 `.env`：

```
ANTHROPIC_BASE_URL=https://open.bigmodel.cn/api/anthropic
MODEL_ID=glm-5.3
```

API key 走环境变量，**不要写进 `.env`**：

```bash
read -rsp "key: " ANTHROPIC_API_KEY && export ANTHROPIC_API_KEY && echo
```

`.env` 的优先级高于环境变量（对齐 s1 的 `load_dotenv(override=True)`），所以 `.env` 里写了同名项会把环境变量顶掉。

`ANTHROPIC_BASE_URL` 只写到域名段，客户端会自行拼接 `/v1/messages`。不填则默认 `https://api.anthropic.com`。

### 可用模型

客户端只说 Anthropic Messages 协议，因此**任何支持 Anthropic 格式的模型**都可以接入——官方 Claude 系列，以及各厂商提供的 Anthropic 兼容端点均可。

本项目设计时使用的是 **GLM Coding Plan**（智谱）的 **GLM-5.3**，即上文 `.env` 示例那组配置：`ANTHROPIC_BASE_URL=https://open.bigmodel.cn/api/anthropic`、`MODEL_ID=glm-5.3`。

## 构建运行

### 方式一：打包成可执行 JAR

```bash
mvn package
java -jar target/opendoggo-0.1.0-SNAPSHOT.jar
```

## 工具

工具实现 `ToolHandler` 接口（`name` / `description` / `inputSchema` / `execute`），在 `Main` 中注册进 `ToolDispatch`。发给模型的 `tools` 数组由注册表自动生成，新增工具不需要改客户端代码。

每次工具执行前，控制台先打印 `> 工具名` 横幅（方便确认是哪个工具在跑），再显示最多 200 字符的结果预览；完整结果仍照常回传给模型。

### bash

在启动目录执行 shell 命令（Linux/WSL 走 `/bin/sh -lc`，Windows 走 `cmd.exe`）。

- 参数：`command`（string，必填）
- 120s 超时，超时强杀整个进程树；stdout 与 stderr 合并，输出上限 50000 字符；无输出返回 `(no output)`
- 工具自身不带策略；危险命令由权限管线在执行前拦截（见下文[权限](#权限)）

### read_file

读取工作区内文件内容，UTF-8。

- 参数：`path`（string，必填）、`limit`（integer，可选）
- 给了 `limit` 且小于总行数时，只返回前 `limit` 行并附 `... (N more lines)`

### write_file

把内容写入工作区内文件，父目录不存在时自动创建。

- 参数：`path`、`content`（string，均必填）
- 成功返回 `Wrote N bytes to path`，N 为 UTF-8 字节数

### edit_file

把文件中**第一处** `old_text` 精确替换为 `new_text`。

- 参数：`path`、`old_text`、`new_text`（string，均必填）
- `old_text` 不存在返回 `Error: text not found`；`old_text` 为空串直接拒绝（否则会变成在文件头插入）

### glob

按 glob 模式查找文件，模式相对工作区根，`**` 递归匹配。

- 参数：`pattern`（string，必填）
- 只列常规文件，按路径排序，最多 150 条，超出提示收窄模式；结果天然限定在工作区内

### 路径解析

`read_file` / `write_file` / `edit_file` 的路径统一经 `WorkspacePaths` 解析：相对工作区归一化，空路径报错（`Error: path cannot be empty`）。s02 曾在工具内直接拒绝逸出工作区的路径；s03 起该条件上移为权限规则（闸门 2），用户批准后可访问工作区外路径。

## 权限

s03 权限管线（`io.opendoggo.permission.PermissionChecker`）插在 `AgentLoop` 的工具执行之前，每个 tool_use 依次经过三道闸门，全部未命中才直接执行：

| 闸门 | 检查 | 命中后果 |
|------|------|----------|
| 1. 硬拒绝表 | 7 条子串（`rm -rf /`、`sudo`、`shutdown`、`reboot`、`mkfs`、`dd if=`、`> /dev/sda`），仅对 bash | 直接拒绝，不询问 |
| 2. 规则匹配 | 文件工具路径逸出工作区；bash 命中破坏性正则（`rm`/`del` 为独立命令词，`model`、`echo del x` 不误伤）或关键字 `rm `、`> /etc/`、`chmod 777`；bash 引用工作区外路径（`..` 段、`~`、工作区外绝对路径，教学级 token 启发式） | 升级到闸门 3 |
| 3. 用户审批 | 打印原因与工具调用，提示 `Allow? [y/N]` | 默认拒绝，仅 y/yes 放行 |

- 未登记规则的工具（如 `glob`）三道闸门都不会命中，直接放行
- bash 跨区检测按空白切分命令逐 token 判断：引号内含空格的路径、等号粘连的选项（如 `--prefix=/x`）识别不了
- 被拒调用仍回传带原 `tool_use_id` 的 `tool_result`，内容为 `Permission denied.`，消息协议不变
- 审批交互经 `ApprovalPrompt` 回调注入，权限层与 `AgentLoop` 不直接碰控制台
- 同 s02：子串与正则匹配属教学级防护，不是安全边界

### 用法示例

工具不直接暴露给用户，模型按需自行选择。REPL 里用自然语言描述即可：

```
doggo >> 把 README.md 的前 5 行读给我
> read_file
# OpenDoggo ...

doggo >> 创建 notes/todo.txt，内容为 buy milk
> write_file
Wrote 8 bytes to notes/todo.txt

doggo >> 把 todo.txt 里的 milk 换成 coffee
> edit_file
Edited notes/todo.txt

doggo >> src 下有哪些 .java 文件？
> glob
agent/AgentLoop.java
...
```

## 结构

```
io.opendoggo
├── Main                    入口：读配置、装配、REPL、控制台审批
├── agent.AgentLoop         核心循环：调模型、权限检查、分发工具、回灌结果
├── environment.Env         配置加载：.env 优先，回落进程环境变量
├── permission
│   ├── PermissionChecker   三道闸门：硬拒绝表、规则匹配、用户审批
│   └── ApprovalPrompt      闸门 3 审批回调（Main 提供控制台实现）
├── tool
│   ├── ToolHandler         工具接口：name / description / schema / execute
│   ├── ToolDispatch        注册表：按名分发，自动生成 tools 数组
│   └── impl
│       ├── ShellTool       bash：超时、输出截断、进程树清理
│       ├── ReadFileTool    读文件，可限行数
│       ├── WriteFileTool   写文件，自动建父目录
│       ├── EditFileTool    精确替换第一处文本
│       ├── GlobTool        glob 模式找文件
│       └── WorkspacePaths  文件工具共用的路径解析
└── model
    ├── ModelClient           模型客户端接口
    ├── impl.AnthropicClient  Messages API 实现（仅 JDK HttpClient）
    ├── Message               对话消息（role + JSON content）
    ├── ContentBlock          内容块（text / tool_use）
    ├── ModelResponse         模型回复，可提取工具调用与文本
    └── ToolResult            工具执行结果
```

## 注意

**启动目录即 agent 工作区。** bash 命令是真实执行的，具有写权限。权限管线的拒绝表与规则匹配是教学级子串/正则防护，容易绕过，不构成安全边界；闸门 3 的默认拒绝只是审批提示，不是强隔离。请勿在重要目录直接运行。

**子进程继承环境变量。** agent 执行的 shell 可读取 `ANTHROPIC_API_KEY`，存在 prompt injection 泄露风险。

## 许可

见 [LICENSE](LICENSE)。

## 参考

- [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) — 本项目参考的教学课程，按其环节逐步实现：s01 agent loop、s02 工具调度、s03 权限管线、s04 hooks，以及后续的 TodoWrite / 子 agent / skill 等。