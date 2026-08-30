更新 `/mnt/c/users/57178/Desktop/母项目/OpenDoggo/AGENTS.md`（英文保持不变），按用户要求概括 s03 相比 s02 的新增内容，并写成 Java 升级指引。用分段 Edit 原地更新，不整篇重写。

## 事实核查结果（写入文件的依据）

1. `.zcode/AGENTS.md`、`.agents/AGENTS.md` 均不存在，可直接更新根目录 AGENTS.md。
2. `references/` 现为 s03（permission）课：`code.py` + `README.zh.md`，s02 代码内嵌在 code.py 的 "From s02" 段。
3. Java 现状（AGENTS.md 旧文说"停在 s01"已过时）：
   - `tool.ToolDispatch`/`ToolHandler` 注册表已存在，`toolDefinitions()` 自动生成 tools 数组；
   - `Main` 只注册 `ShellTool`；`GlobTool` 已实现但未注册；
   - `read_file`/`write_file`/`edit_file` 不存在；
   - 拒绝列表（5 条子串）仍内嵌在 `ShellTool` 中。
4. s03 相对 s02 的全部新增（来自 code.py 137-237 行）：
   - 循环里只加一行 `check_permission()`，插在工具执行之前；工具集与分发机制不变；
   - 闸门 1 硬拒绝表（7 条：`rm -rf /`、`sudo`、`shutdown`、`reboot`、`mkfs`、`dd if=`、`> /dev/sda`），仅对 bash，子串匹配，直接拒绝不询问；
   - 闸门 2 规则匹配：file tools 路径解析后逸出工作区；bash 命中破坏性命令正则（`rm`/`del` 作为独立命令词，兼容 Windows del）或关键字（`rm `、`> /etc/`、`chmod 777`）。命中不当场拒绝，而是升级到闸门 3；
   - 闸门 3 用户审批：打印原因和工具调用，提示 `Allow? [y/N]`，默认拒绝；
   - 被拒调用仍返回携带原 `tool_use_id` 的 tool_result，内容为 `Permission denied.`，消息协议不变；
   - 路径限制从工具内部移到规则层：s02 中 file tools 自行拒绝工作区外路径，s03 变成可由用户批准的规则；
   - 系统提示词新增 "All destructive operations require user approval."

## AGENTS.md 目标内容（分节 Edit）

1. **Project Boundary** — 更新现状：references/ 现为 s03 课（已替换 s02 目录）；Java 处于 s02 中途（注册表已有、只注册 bash、GlobTool 未接线、file tools 缺失）。
2. **Current Architecture** — 修正：`tool.ToolDispatch` 注册表 + `toolDefinitions()` 自动生成 schema（加工具=实现 ToolHandler 并在 Main 注册）；ShellTool 的 5 条内部拒绝列表标注"待抽到权限层"；README 架构树过时说明保留并补充。
3. **s02 → s03 section（替换原 "s01 to s02"）** — 概括上述第 4 点的全部新增内容（三道闸门、协议不变、containment 移位、系统提示词变化）。
4. **Java Upgrade Path section（新增）** — 指导后续完善代码的五步：
   1. 先补完 s02：实现 `ReadFileTool`/`WriteFileTool`/`EditFileTool`，并把 `GlobTool` 注册进 `Main`；
   2. 把 `ShellTool` 内嵌拒绝列表抽成 `io.opendoggo.permission.PermissionChecker`，由 `AgentLoop` 在 `toolDispatch.execute()` 之前调用，拒绝列表扩为 s03 的 7 条；工具自身不再携带策略；
   3. 闸门 3 需要控制台：由 `Main` 的 REPL 注入审批回调接口，保持 `AgentLoop` 无 UI；
   4. 拒绝时追加 `ToolResult(toolCall.id(), "Permission denied.")`，与正常结果同形；
   5. 更新 `Main` 中系统提示词。源码真值：`references/code.py`，课理：`references/README.zh.md`。
5. **Commands / Configuration and Safety** — 命令不变；安全段把"ShellTool 五条子串"改写为"拒绝/规则检查均为教学级子串匹配"。

完成后向用户总结各节要点并给出文件路径。全程只改 AGENTS.md 一个文件。