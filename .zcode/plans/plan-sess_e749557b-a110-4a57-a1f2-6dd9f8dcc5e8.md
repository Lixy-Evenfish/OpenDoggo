## 新增 bash 跨工作区访问规则（闸门 2）

### 改动清单

1. **`PermissionChecker.java`**：
   - `rules` 列表新增一条 bash 规则：`input -> referencesOutsideWorkspace(text(input, "command"))`，消息 `Access outside workspace`（与文件工具规则一致，命中后升级闸门 3 询问，默认拒绝）；
   - 新增私有辅助方法 `referencesOutsideWorkspace(String command)`：按空白切分命令、剥掉 token 首尾引号，token 满足任一条件即视为跨区——等于 `..` 或以 `../` 开头或含 `/../` 段；以 `~` 开头；以 `/` 开头但既不是工作区路径本身也不是其子路径（用 `equals(workdir) || startsWith(workdir + "/")` 判定边界，避免前缀巧合误放行）。
2. **冒烟测试扩展**（`/tmp/opendoggo-smoke/Smoke.java`，不进仓库）：新增断言——`mkdir ../跨工作区文件夹` 触发审批、答 n 拒绝 / 答 y 放行；工作区内绝对路径命令直通；`cat /etc/passwd` 触发审批；`ls -la src/main` 纯相对路径直通。
3. **验证**：`mvn compile` + 重跑冒烟测试全部 PASS。
4. **文档**：`README.md` 权限表闸门 2 行补 bash 跨区检测并注明局限（引号含空格路径、等号粘附件不识别，教学级启发式）；`AGENTS.md` s03 章节 Gate 2 描述同步。

### 行为变化说明

- 之前：`mkdir ../x`、`cat /etc/passwd` 等直接执行；
- 之后：命中跨区启发式 → 打印 `Access outside workspace` → `Allow? [y/N]`，批准即可执行（与 write_file 跨区行为完全一致），拒绝则返回 `Permission denied.`。
- 不改变其他规则；`glob` 等未登记工具仍直接放行。