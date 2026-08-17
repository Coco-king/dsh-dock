# AGENTS.md — 项目规范（AI 智能体必读）

> 本文件供 AI 智能体（及协作开发者）在修改本仓库前阅读。**修改代码、文档、版本号前请先读完本文件。**

## 项目简介

Dsh Dock 是一个 IntelliJ IDEA 插件：一键启动 DeepSeek Harness（`dsh web`），并把 WebUI 显示在工具窗口内嵌的 JCEF 浏览器中。支持 **WSL 中启动**与 **Windows 直接启动**两种方式。

- 语言：Kotlin（JVM target 11，编译 JDK 21）
- 兼容：IntelliJ IDEA 2022.3 (223) 及以上（`pluginSinceBuild = 223`，无版本上限）
- 构建：`gradlew buildPlugin`（需要 JDK 17+ 的 Gradle 运行环境；本机 JDK 路径、构建方式等个性化配置见 **AGENTS.local.md**）
- 开源地址：<https://gitee.com/kkcoco/dsh-idea-plugin>（`gradle.properties` 的 `pluginRepositoryUrl`）

## 本机个性化配置（AGENTS.local.md）

- 机器相关的个性化配置（JDK 路径、构建工具、IDE 位置等）统一写在仓库根目录的 **`AGENTS.local.md`**；
- 该文件已加入 `.gitignore`，**不会提交到 git**，但本机智能体可以直接读取它；
- 智能体开始工作前应先读取 `AGENTS.local.md`（存在时）并遵守其中的本机约定；它只描述**当前开发机**的环境，不要求其他机器一致；
- 新机器 clone 后按需自建 `AGENTS.local.md`（通用构建说明见下文「构建与验证」）。

## 核心约定

### 1. 设置按启动方式（WSL / Windows）分别配置

端口与附加参数**按启动方式分离**，禁止改回单一的 `port` / `extraDshArgs`：

| 字段 | 含义 | 默认值 |
| --- | --- | --- |
| `wslPort` / `windowsPort` | 各启动方式的 dsh web 监听端口 | **3090** / **3080** |
| `wslExtraDshArgs` / `windowsExtraDshArgs` | 各启动方式的附加参数 | 空 |

- 统一通过 `DshSettingsState` 的访问器取值：`portFor(mode)` / `extraArgsFor(mode)` / `currentPort()` / `currentExtraArgs()`
- 启动方式常量：`DshSettingsState.MODE_WSL` / `MODE_WINDOWS`（不要散落字符串字面量 `"wsl"` / `"windows"`）

### 2. CHANGELOG.md 规范（重要：存在渲染限制）

**每个版本的结构必须是这样：**

```markdown
## [x.y.z] - YYYY-MM-DD

### 中文 (Chinese)

- **变更（Changed）**：……
- **修复（Fixed）**：……

### English

- **Changed**: ……
- **Fixed**: ……
```

**必须遵守的规则：**

1. **中英分组，不混排**：中文一段、英文一段。语言作为 `###` 分组标题（`中文 (Chinese)` / `English`）。
2. **变更类型写在每条 bullet 的开头加粗前缀**：`**变更（Changed）**` / `**修复（Fixed）**` / `**新增（Added）**`（英文：`**Changed**` / `**Fixed**` / `**Added**`）。
3. **⚠️ 解析器限制（为什么必须是上面的结构）**：gradle-changelog 解析器（基于 IntelliJ Markdown AST）在每个 `###` 组内**只渲染第一个列表**。因此：
   - 禁止把 `**中文**` 之类的加粗段落或 `####` 标题插在两个列表之间——后面的列表（如英文段）会在插件变更记录渲染时**被整体丢弃**；
   - 禁止在同一个 `###` 组内连续放两个列表；
   - 语言必须用 `###` 分组，变更类型用 bullet 前缀，二者缺一不可。
4. **中英文条目一一对应**，内容语义一致，不能只改一边。
5. `[Unreleased]` 区同样遵循以上结构（已发布的版本记得标注发布日期）。

### 3. README.md 插件描述规范

`<!-- Plugin description -->` 与 `<!-- Plugin description end -->` 之间的内容会被提取为插件清单描述（markdown → HTML），**不要删除这两个标记**。

- 结构：`**中文**` 组 + `**English**` 组，每组内**按功能拆分为带序号的列表**（`1.` `2.` `3.` …），不要写成长段落；
- 中英文条目**一一对应**（相同的序号、相同的功能点），改翻译必须两边同步；
- 新增/删改功能时保持编号列表与正文（## 功能 / 使用 / 设置 / 原理 / 常见问题）一致。

### 4. 版本升级与 CHANGELOG 同步（每次都要做）

- 修改代码后，**如果要升级版本**（`gradle.properties` 的 `pluginVersion`），**必须同步**把本次全部修改**以中英文双语写入 CHANGELOG.md**（写入对应新版本段落，或先写进 `[Unreleased]`）；
- 不允许"只改版本号、不改 CHANGELOG"的提交；
- 版本号遵循 semver；发布时把 `[Unreleased]` 内容整理到带日期的正式版本段落。

### 5. 文档侧改动也要按规范

- 更新 README 设置表/功能列表时，注意默认端口是 **WSL `3090` / Windows `3080`**；
- 修改描述块或 CHANGELOG 后，建议运行 `gradlew patchPluginXml` / `buildPlugin` 验证 change-notes 与 description 的 HTML 渲染（可用 `build/tmp/patchPluginXml/plugin.xml` 检查渲染结果）。

### 6. 代码风格

- Kotlin，注释与 KDoc 用中文；UI 用 Swing `GridBagLayout`（不引入三方 UI 库）；
- 保持"不绑定本机路径"原则：WSL 发行版、NVM 路径等一律不配置、不持久化；
- 提交信息用 `feat(scope): ……` 风格，正文可中英混排、要点式列出。