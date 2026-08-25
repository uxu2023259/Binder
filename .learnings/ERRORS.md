# Errors

Command failures and integration errors.

---

## 2026-07-24 - Maven 环境未配置 Java 21

- 现象：Maven 提示 `JAVA_HOME` 未正确设置，无法执行依赖解析。
- 原因：当前终端的 Java 不在 PATH，且 Maven 进程没有使用已检测到的 JDK 21 路径。
- 后续：使用明确的 JDK 21 安装目录设置 `JAVA_HOME`，再执行构建验证。

---

## 2026-07-24 - Paper 旧版构建接口已下线

- 现象：请求 `api.papermc.io/v2/projects/paper/versions/1.21.11/builds` 返回 `sunset`。
- 处理：改用 Paper 当前的 `fill.papermc.io/v3/projects/paper/versions/1.21.11/builds` 接口，已取得稳定构建 132。

---

## 2026-07-24 - Paper 烟雾测试脚本输入重定向失败

- 现象：PowerShell `Start-Process` 拒绝尚未存在的标准输入重定向文件，导致服务端未启动。
- 处理：启动进程前显式创建空的输入文件，再重试服务端加载测试。

---

## 2026-05-04 - Maven 编译失败：InventoryType 导入包错误

- 命令：`mvn -DskipTests package`
- 现象：`org.bukkit.inventory.InventoryType` 不存在。
- 修正：Paper/Bukkit 的 `InventoryType` 应从 `org.bukkit.event.inventory.InventoryType` 导入。

---


## 2026-05-04 - CoreProtect performPartialLookup 修改不可变列表导致异常

- 现象：运行 `/bind` 打开 GUI 时，CoreProtect 23.4b 抛出 `UnsupportedOperationException`，栈中显示 `ImmutableCollections.removeIf`。
- 原因：传给 CoreProtect API 的 `action_list` 使用了 `List.of(...)`，但 CoreProtect 内部会对该列表执行 `removeIf`。
- 修正：传入 CoreProtect 的过滤列表统一使用可变 `ArrayList`。

---

## 2026-05-05 - 非 Git 仓库中调用 git diff 失败

- 命令：`git diff -- ...`
- 现象：当前 Binder 目录不是 Git 仓库，Git 返回用法说明。
- 修正：本项目检查改用精确文件读取、`C:\ripgrep\rg.exe` 搜索和 Maven 构建验证，不依赖 Git diff。

---
## 2026-05-05 - ripgrep 复合正则引号导致解析失败

- 命令：`& 'C:\ripgrep\rg.exe' -n "...case \"list\"..." ...`
- 现象：正则被 PowerShell 引号处理后变成未闭合分组。
- 修正：包含大量引号的代码搜索优先拆成多个固定字符串，或使用 `-F` 固定字符串模式。

---
## 2026-05-05 - PowerShell 一次性写入大 Java 文件超过命令长度

- 命令：使用长 here-string 通过 `Set-Content` 重写 `BinderGui.java`。
- 现象：Windows 返回“文件名或扩展名太长”，实际是命令行长度超过限制。
- 修正：大文件改用分段 `Set-Content` + `Add-Content` 写入，或使用补丁/脚本分块生成。

---
## [ERR-20260505-001] git_diff_not_repo

**Logged**: 2026-05-05T01:35:00+08:00
**Priority**: low
**Status**: resolved
**Area**: infra

### Summary
在当前 Binder 目录执行 `git diff -- <多个文件>` 返回用法错误，不能作为必要验证步骤。

### Error
```text
warning: Limiting comparison with pathspecs is only supported if both paths are directories.
```

### Context
- 尝试查看本次源码差异。
- 当前目录没有可用的标准 Git diff 工作流，后续验证改用 Maven 构建、定向搜索和产物检查。

### Suggested Fix
不要依赖 `git diff` 验证该项目改动；使用 `mvn -DskipTests package`、`C:\ripgrep\rg.exe` 搜索和 jar 内容检查。

### Metadata
- Reproducible: yes
- Related Files: C:\Users\hyx\Desktop\Binder

### Resolution
- **Resolved**: 2026-05-05T01:35:00+08:00
- **Notes**: 已改用构建验证和产物检查。

---

## [ERR-20260505-001] git_diff_not_repository

**Logged**: 2026-05-05T02:43:30+08:00
**Priority**: low
**Status**: resolved
**Area**: infra

### Summary
在 Binder 目录尝试使用 `git diff` 汇总改动时失败，因为该目录不是普通 Git 工作区。

### Error
```text
git diff -- src/main/java/awa/uxu/BindingService.java src/main/java/awa/uxu/BinderGui.java README.md pom.xml src/main/resources/plugin.yml
返回用法信息：Limiting comparison with pathspecs is only supported if both paths are directories.
```

### Context
- 任务：发布前功能增量后尝试查看改动摘要。
- 环境：C:\Users\hyx\Desktop\Binder。

### Suggested Fix
后续在该目录中不要依赖 `git diff`；改用构建、jar 内 `plugin.yml` 核验和定点文件检查确认结果。

### Metadata
- Reproducible: yes
- Related Files: C:\Users\hyx\Desktop\Binder

### Resolution
- **Resolved**: 2026-05-05T02:43:30+08:00
- **Notes**: 已改用 Maven 构建和 jar 内容核验确认产物。

---
## [ERR-20260505-001] PowerShell 正则引号转义失败

**Logged**: 2026-05-05T03:20:00+08:00
**Context**: 使用双引号包裹包含复杂字符类的 ripgrep 正则时，PowerShell 将方括号解析为索引表达式。
**Fix**: 对复杂 ripgrep 正则使用单引号包裹，必要时拆成多条简单搜索。

## [ERR-20260505-001] powershell_select_object_range

**Logged**: 2026-05-05T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: infra

### Summary
PowerShell 中 Select-Object -Index 不能直接接收未加括号的 540..700 字符串形式；记录日志时也应避免在双引号里误展开 `$lines[540..700]`。

### Error
```text
Cannot bind parameter 'Index'. Cannot convert value "540..700" to type "System.Int32".
```

### Context
- 尝试读取 BindingService.java 指定行范围。
- 随后用双引号拼接 Markdown 时触发了不必要的变量展开。

### Suggested Fix
使用 `-Index (540..700)`，或通过数组下标 `$lines[540..700]`；写多行 Markdown 优先用单引号 here-string。

### Metadata
- Reproducible: yes
- Related Files: C:\Users\hyx\Desktop\Binder\src\main\java\awa\uxu\BindingService.java

### Resolution
- **Resolved**: 2026-05-05T00:00:00+08:00
- **Notes**: 后续改用正确数组范围与单引号 here-string。

---

## [ERR-20260505-002] powershell_literal_backtick_rn

**Logged**: 2026-05-05T00:00:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: infra

### Summary
PowerShell 中用单引号或不当替换字符串时，可能把 `` `r`n `` 作为字面文本写进 Java 源码。

### Error
```text
store.save();`r`n        if (!store.checkpoint()) {
```

### Context
- 批量把 `store.save()` 替换为 `store.saveDirty()` 后，想恢复备份路径为完整保存。
- 替换字符串使用方式不当，导致字面 `` `r`n `` 进入文件。

### Suggested Fix
源码替换需要换行时使用双引号替换值或直接用 `.Replace()` 精确修复，并立即查看相关行。

### Metadata
- Reproducible: yes
- Related Files: C:\Users\hyx\Desktop\Binder\src\main\java\awa\uxu\BindingService.java

### Resolution
- **Resolved**: 2026-05-05T00:00:00+08:00
- **Notes**: 已替换为真实换行并核查行内容。

---
## [ERR-20260505-001] ripgrep_multiline_regex

**Logged**: 2026-05-05T09:20:00+08:00
**Priority**: low
**Status**: pending
**Area**: infra

### Summary
使用 ripgrep 搜索跨行正则时，如果未启用多行模式会失败。

### Error
```text
rg: the literal "\n" is not allowed in a regex
```

### Context
- 在 Binder 项目中按 AGENTS.md 使用 `C:\ripgrep\rg.exe` 搜索 `store.markDirty(record)` 与下一行 `store.saveDirty()` 的跨行模式。
- 默认 ripgrep 正则不允许字面换行。

### Suggested Fix
搜索跨行模式时要么使用 `-U`/`--multiline`，要么改为 `-C` 上下文搜索再人工查看。

### Metadata
- Reproducible: yes
- Related Files: C:\Users\hyx\Desktop\Binder\AGENTS.md

---
## [ERR-20260505-CLICKTYPE] Maven 编译失败：ClickType 枚举名不兼容

**Logged**: 2026-05-05T13:55:00+08:00
**Priority**: low
**Status**: resolved
**Area**: backend

### Summary
Paper API 1.20.4 中双击枚举名为 `DOUBLE_CLICK`，不是 `DOUBLE`。

### Error
```text
找不到符号：变量 DOUBLE，位置：类 org.bukkit.event.inventory.ClickType
```

### Context
- 命令：`mvn -q -DskipTests package`
- 修改库存点击全量更新判定时误用了不存在的枚举常量。

### Suggested Fix
使用 `org.bukkit.event.inventory.ClickType.DOUBLE_CLICK`，必要时先用 `javap` 查看本地 API 枚举。

### Metadata
- Reproducible: yes
- Related Files: src/main/java/awa/uxu/BinderListener.java

### Resolution
- **Resolved**: 2026-05-05T13:56:00+08:00
- **Notes**: 已替换为 `DOUBLE_CLICK`。

---
