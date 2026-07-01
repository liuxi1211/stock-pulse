---
alwaysApply: false
description: "当用户涉及 Git 操作、代码提交、分支管理、版本标签、.gitignore 配置等场景时触发。适用于编写 commit message、创建分支、合并代码、打标签、配置忽略文件等任务。关键词：git, commit, branch, 提交, 分支, 版本, tag, 代码合并"
---
# Git 提交规范

> 适用于 Stock 项目所有子系统的 Git 分支与提交规范。

---

## 一、分支命名规范

### 1.1 分支类型与前缀 ✅ MUST

| 类型 | 前缀 | 示例 | 说明 |
|-----|------|------|------|
| 功能 | `feature/` | `feature/add-watchlist` | 新功能开发 |
| 修复 | `fix/` | `fix/kline-calc-error` | Bug 修复 |
| 重构 | `refactor/` | `refactor/tushare-client` | 代码重构，无功能变更 |
| 文档 | `docs/` | `docs/update-readme` | 文档更新 |
| 性能 | `perf/` | `perf/optimize-daily-query` | 性能优化 |
| 测试 | `test/` | `test/add-auth-tests` | 测试相关 |
| 杂项 | `chore/` | `chore/upgrade-spring-boot` | 构建工具、依赖升级等 |

### 1.2 命名约定 ✅ MUST

- 使用 **小写英文 + 连字符（kebab-case）**
- 简洁描述分支内容，不超过 50 字符
- 避免模糊描述（如 `fix-bug`、`update`）

### 1.3 反例 ❌

```
# 不好的命名
fix-bug
update
feature123
dev_arthur
20240630_feature
```

---

## 二、Commit Message 规范

采用 **Conventional Commits** 格式。

### 2.1 格式 ✅ MUST

```
<type>(<scope>): <subject>

<body>

<footer>
```

### 2.2 Type（类型）✅ MUST

| type | 说明 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `docs` | 文档变更 |
| `style` | 代码格式调整（不影响代码逻辑） |
| `refactor` | 重构（既不新增功能也不修 Bug） |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `chore` | 构建过程、辅助工具、依赖变动等 |
| `revert` | 回滚之前的提交 |

### 2.3 Scope（范围）💡 SHOULD

- 可选，指明影响的模块
- 建议使用模块名或目录名
- 示例：`auth`、`kline`、`tushare`、`watchlist`

### 2.4 Subject（主题）✅ MUST

- 简短描述，不超过 50 字符
- 使用祈使句（动词开头）
- 句末不加句号
- 首字母小写

### 2.5 Body（正文）💡 SHOULD

- 详细描述变更内容，可分多行
- 说明变更的原因和思路
- 每行不超过 72 字符

### 2.6 Footer（页脚）📌 MAY

- 不兼容变更：以 `BREAKING CHANGE:` 开头
- 关联 Issue：`Closes #123`、`Refs #456`

### 2.7 示例

```
feat(watchlist): 支持批量添加自选股

- 新增批量添加接口
- 前端支持多选操作
- 添加接口限流保护

Closes #42
```

```
fix(kline): 修复前复权计算精度问题

使用 BigDecimal 替代 double 进行复权计算，
避免浮点误差导致价格不连续。

Closes #56
```

```
chore: 升级 Spring Boot 到 4.0.7

- 升级 spring-boot-starter-parent
- 兼容 MyBatis-Plus 最新版本
```

---

## 三、提交原则

### 3.1 原子提交 ✅ MUST

- 每次提交只做一件事
- 不把多个无关变更塞进同一个提交
- 功能开发按子功能分批提交

### 3.2 提交频率 💡 SHOULD

- 完成一个小的可验证单元就提交
- 避免攒一大堆才提交
- 不提交半成品（不能编译 / 不能运行的代码）

### 3.3 提交前检查 ✅ MUST

- 代码能正常编译/运行
- 没有遗留的 debug 代码和 println
- 没有硬编码的敏感信息
- 注释和文档已同步更新

---

## 四、.gitignore 规范

### 4.1 必须忽略的文件 ✅ MUST

```
# 构建产物
target/
build/
dist/
*.class
*.jar
*.war

#  IDE 配置
.idea/
.vscode/
*.iml
*.iws
*.ipr

# 日志与数据
*.log
data/*.db
data/logs/

# 环境配置（含敏感信息）
.env
.env.local
application-secret.properties

# 系统文件
.DS_Store
Thumbs.db

# Python
__pycache__/
*.pyc
*.pyo
*.pyd
venv/
env/
.venv/
*.egg-info/

# Node
node_modules/
```

### 4.2 模板文件 💡 SHOULD

- 敏感配置提供模板文件，如 `.env.example`、`application-secret.properties.template`
- 模板文件中填写占位说明，不包含真实值

---

## 五、版本标签（可选）

### 5.1 语义化版本 📌 MAY

采用 Semantic Versioning：`MAJOR.MINOR.PATCH`

- **MAJOR**：不兼容的 API 变更
- **MINOR**：向下兼容的功能性新增
- **PATCH**：向下兼容的问题修正

示例：`v1.0.0`、`v1.1.0`、`v1.1.1`
