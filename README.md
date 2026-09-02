# OpenDoggo

南大软院推免项目，开源 harness，Java 实现的最小 AI coding agent。

## v1

v1 版本的目标是初步实现 agent loop 的核心闭环，完成基本的工具调度和权限控制：

```
模型 -> 工具调用 -> 执行 -> 结果回灌 -> 模型 -> ... -> 无工具调用则结束
```

功能范围：

- **Agent Loop主循环** — 核心循环支持 Anthropic Messages 格式的 AI 调用（仅 JDK `HttpClient`，无 SDK 依赖）：模型请求工具就继续循环，不请求就返回最终回答，上限 50 轮；单次回复中的多个 tool_use 依次执行后一并回传。外层是全屏 TUI，多轮对话，历史驻留内存
- **工具注册与调度** — 工具实现统一的 `ToolHandler` 接口、注册进 `ToolDispatch` 按名分发；发给模型的 `tools` 数组由注册表自动生成，新增工具不需要改客户端代码
- **五种具体工具** — `bash` / `read_file` / `write_file` / `edit_file` / `glob`，覆盖命令执行、读、写、精确编辑与文件查找（详见下文[工具](#工具)一节）
- **权限控制与错误回退** — 最基础的工具权限控制：执行前依次过硬拒绝表、规则匹配、用户审批三道闸门（详见下文[权限](#权限)一节）；单轮失败时回滚历史，避免残留消息污染后续请求

## v2 

v2 版本的目标是引入循环钩子机制让 agent loop 成为稳定内核，并实现 TodoWrite 计划机制、SubAgent 任务委派、Skill 技能加载这 3 个常见的工具。

新增功能：

- **Hooks循环钩子** — Agent loop 引入 hook 和 trigger ，在 4 个关键位置（`UserPromptSubmit` / `PreToolUse` / `PostToolUse` / `Stop`）设置挂载点，`AgentLoop` 的代码就此固定，后续扩展只需在 `Main` 注册 hook（权限、横幅、预览已挂上，示例 hook 补全中）
- **TodoWrite计划机制**— 在多步实现的复杂任务中，引导 LLM 显式维护任务清单，防止 LLM 中途跑偏
- **SubAgent任务委派**— 允许 LLM 通过调用工具的方法，把独立子任务委派给 subagent ，使得上下文更干净
- **Skill技能加载** — 按需知识加载，启动时扫描 `skills/` 把技能目录（名称+描述）写进 system prompt，模型判断相关时经 `load_skill` 工具取回完整 `SKILL.md`（详见下文[技能](#技能)一节），避免一次性给出过长 prompt ，缩短上下文。

## v3

v3 版本的目标是给 agent 增加 ContextCompact 上下文压缩、MCP tools 这 2 个进阶级的拓展，以及代码沙箱工具。

新增功能：
- **ContextCompact上下文压缩** — 上下文总会满，先整理、再总结。每轮模型调用前运行压缩管线，五个策略按成本递进：
  1. **大结果落盘**：最新一批工具结果总量超 200k 时，超 30k 的结果全文写入 `.task_outputs/tool-results/`，上下文只留路径 + 2,000 字符预览；
  2. **消息归档**：历史超 50 条时保留头 3 + 尾 46，中段全量写入 `.transcripts/` 并原位换成归档标记（切点保护 tool_use/tool_result 配对）；
  3. **旧结果缩短**：字符估算超 50k（目标 40k）时，已读旧结果保留最近 3 条、更早的换成可恢复路径引用，未读大结果换 1,000 字符预览；
  4. **历史摘要**：整理后仍超限，花一次专用模型调用生成事实摘要，历史替换为单条 `[Compacted]` 消息（当前请求与摘要分区，防提示注入）；
  5. **响应式补救**：API 报 `prompt_too_long` 时保留最近 5 条、摘要更早历史，重试一次。
  策略 1–3 零额外 API 调用且全部可从磁盘恢复；模型还可用 `compact` 工具在阶段结束时主动请求压缩（等整批结果入史后执行摘要）。
- **单进程模拟 MCP tools** — 模型调 `connect_mcp` 连接 server，`McpClient` 充当 tools/list 与 tools/call 的进程内替身；`McpRegistry` 把工具以 `mcp__{server}__{tool}` 前缀登记进分发表并刷新 client 的 tools 数组，下一轮请求即可调用。授权只认宿主侧策略表，server 自标的 `readOnlyHint` 不作为依据，未配置默认需用户审批。
- **长任务放后台** — 后台线程执行命令，后续轮次收集完成结果。
- **代码沙箱** — 新增 `run_code` 工具，把代码放进一次性容器执行，以机制隔离宿主风险：无网络、根文件系统只读、仅挂载本次运行的临时目录、非特权用户、资源限额与双层超时；容器不继承宿主环境变量，密钥进不了沙箱。权限管线靠用户审批（策略），沙箱靠机制保证出不去——隔离代替审批，因此免审批。需本机 Docker，支持 Python、Node.js、Bash。

## v4

v4 提供极简全屏 TUI：迎宾页只有标题和输入框，首次提交后进入带滚动聊天记录和固定输入框的对话页。对话页底部显示当前工作目录，`Doggo` 标签后显示该轮总耗时。界面不显示模型、模式、token、费用等附加信息；危险操作仍通过审批浮层确认。


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

`Enter` 发送，`↑`/`↓` 或 `PageUp`/`PageDown` 滚动聊天记录，`Esc` 或 `Ctrl+C` 退出。审批浮层中按 `Y` 允许，按 `N` 或 `Enter` 拒绝。

## 工具

工具实现 `ToolHandler` 接口（`name` / `description` / `inputSchema` / `execute`），在 `Main` 中注册进 `ToolDispatch`。发给模型的 `tools` 数组由注册表自动生成，新增工具不需要改客户端代码。

工具调用及完整结果仍回传给模型；TUI 会在聊天记录中显示每个工具名和最多 200 个字符的结果预览，包括被拒绝的调用和循环内处理的 `compact`。

### bash

在启动目录执行 shell 命令（Linux/WSL 走 `/bin/sh -lc`，Windows 走 `cmd.exe`）。

- 参数：`command`（string，必填）
- 120s 超时，超时强杀整个进程树；stdout 与 stderr 合并，输出上限 50000 字符；无输出返回 `(no output)`
- 工具自身不带策略；危险命令由权限管线在执行前拦截（见下文[权限](#权限)）

### run_code

在一次性容器里执行代码，以机制隔离代替权限审批（不注册权限规则）。执行模型生成的不受信任代码时优先用它，`bash` 只做工作区操作。

- 参数：`language`（枚举：`python` / `node` / `bash`）、`code`（代码字符串，均必填）
- 隔离：无网络；根文件系统只读；仅 `.sandbox/` 下本次运行目录可写；非特权用户；内存、处理器、进程数限额
- 超时：容器内定时自杀、宿主强杀兜底；输出上限两万字符，超限全量落盘并返回文件路径
- 前置：本机 Docker；Python 首次使用前需拉取一次镜像（Node.js 与 Bash 复用已有镜像），缺失时返回错误提示而非静默下载

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

## 技能

技能（skill）是一份按需加载的知识包：一个目录 + 一个 `SKILL.md`。启动时 `SkillLoader`（`io.opendoggo.skill`）扫描 `skills/` 下**恰好一层深**的 `SKILL.md`，把每项的**名称和描述**做成目录写进 system prompt；模型判断某项相关时调用 `load_skill` 工具取回**完整原文**，作为 tool_result 进入上下文。目录常驻、正文按需——无关文档不再占用每次调用的输入 token。

### 添加一个技能

```text
skills/
  my-skill/SKILL.md        ← 一个技能 = 一级子目录 + 固定文件名 SKILL.md
```

`SKILL.md` 顶部是可选的 frontmatter（两行 `---` 包住），正文为任意 Markdown：

```markdown
---
name: my-skill
description: 什么时候用这个技能——目录里唯一的触发依据，写"何时用"而非"是什么"
---

# 完整指令写在这里
步骤、规则、输出格式……
```

- frontmatter 缺省时回退：`name` 取目录名，`description` 取正文首行；description 支持多行块标量（`|` 后接缩进续行），进目录时折叠为单行
- 解析全程容错：格式损坏不报错、走回退；**新增或修改技能后需重启**——技能表是启动时的一次性快照，运行期不刷新
- `load_skill` 的 `name` 只是注册表键，不当文件路径解析；未命中返回 `Error: Unknown skill '...'. Available: ...`，模型会照列表自行纠正

仓库自带四个课程示例技能：`agent-builder`、`code-review`、`mcp-builder`、`pdf`。`agent-builder` 的 `references/`、`scripts/` 附属文件不参与扫描，但模型加载技能后可用 `read_file` 按 SKILL.md 正文里给出的相对路径读取。

### 在 TUI 里怎么用

不需要任何新命令，正常说话即可（工具不直接暴露给用户，模型自行决定何时加载）：

```text
doggo >> What skills are available?
（照 system prompt 里的目录回答，连 load_skill 都不调）

doggo >> Load the code-review skill and follow its instructions
（显式点名，加载后按技能里的步骤干活）

doggo >> Review README.md and load the relevant skill first
（隐式触发：模型读目录里的 description 自行选中 code-review）
```

### 边界

- `load_skill` 只注册在父循环；子代理（`task` 工具派出的）没有它。子任务需要技能知识时，由父代理先加载、把要点提炼进 task 的 prompt 再委派
- 无权限规则：纯内存注册表查询，无路径穿越面，fail-open（同 `todo_write` / `task`）

## 权限

s03 权限管线（`io.opendoggo.permission.PermissionChecker`）插在 `AgentLoop` 的工具执行之前，每个 tool_use 依次经过三道闸门，全部未命中才直接执行：

| 闸门 | 检查 | 命中后果 |
|------|------|----------|
| 1. 硬拒绝表 | 7 条子串（`rm -rf /`、`sudo`、`shutdown`、`reboot`、`mkfs`、`dd if=`、`> /dev/sda`），仅对 bash | 直接拒绝，不询问 |
| 2. 规则匹配 | 文件工具路径逸出工作区；bash 命中破坏性正则（`rm`/`del` 为独立命令词，`model`、`echo del x` 不误伤）或关键字 `rm `、`> /etc/`、`chmod 777`；bash 引用工作区外路径（`..` 段、`~`、工作区外绝对路径，教学级 token 启发式） | 升级到闸门 3 |
| 3. 用户审批 | TUI 浮层显示原因与工具调用 | 默认拒绝，仅 `Y` 放行 |

- 未登记规则的工具（如 `glob`）三道闸门都不会命中，直接放行
- bash 跨区检测按空白切分命令逐 token 判断：引号内含空格的路径、等号粘连的选项（如 `--prefix=/x`）识别不了
- 被拒调用仍回传带原 `tool_use_id` 的 `tool_result`，内容为 `Permission denied.`，消息协议不变
- 审批交互经 `ApprovalPrompt` 回调注入，由 `TerminalTui` 统一读取按键；权限层与 `AgentLoop` 不直接读取终端
- 同 s02：子串与正则匹配属教学级防护，不是安全边界

### 用法示例

工具不直接暴露给用户，模型按需自行选择。TUI 里用自然语言描述即可：

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
├── Main                    入口：读配置并装配 TUI 与 AgentLoop
├── agent.AgentLoop         核心循环：调模型、权限检查、分发工具、回灌结果
├── environment.Env         配置加载：.env 优先，回落进程环境变量
├── permission
│   ├── PermissionChecker   三道闸门：硬拒绝表、规则匹配、用户审批
│   └── ApprovalPrompt      闸门 3 审批回调（TerminalTui 实现）
├── sandbox.SandboxRunner   代码沙箱：一次性容器、资源限额、双层超时
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

**子进程继承环境变量。** agent 执行的 shell（`bash` 工具）可读取 `ANTHROPIC_API_KEY`，存在 prompt injection 泄露风险；跑不受信任的代码请改用 `run_code`，容器不继承宿主环境变量，密钥进不了沙箱。沙箱防御代码越权（文件、网络、资源），不防御内核级容器逃逸。

## 许可

见 [LICENSE](LICENSE)。

## 参考

- [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) — 本项目参考的教学课程，按其环节逐步实现：s01 agent loop、s02 工具调度、s03 权限管线、s04 hooks，以及后续的 TodoWrite / 子 agent / skill 等。
