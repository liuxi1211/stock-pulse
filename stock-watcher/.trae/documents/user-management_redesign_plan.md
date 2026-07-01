# user-management.html 页面改造计划

## 一、改造目标
根据 StockPulse 新设计系统，改造用户管理页面，使其与 dashboard 等页面保持一致的视觉风格和布局结构。

## 二、现状分析

### 原页面结构（旧）
```
d-flex
  ├── sidebar (fragments/common :: sidebar)
  └── main-wrapper
        ├── navbar (fragments/common :: navbar)
        └── content-area
              ├── 搜索卡片 (card)
              └── 表格卡片 (card)
```

### 新设计系统结构（参考 dashboard）
```
app-container
  ├── sidebar (fragments/common :: sidebar)
  ├── sidebarOverlay (fragments/common :: sidebarOverlay)
  └── main-content
        ├── topNavbar (fragments/common :: topNavbar)
        └── content-area
              ├── page-header (page-title + page-actions)
              └── ... 卡片内容 (animate-in 动画)
```

## 三、具体改造内容

### 1. 布局结构改造
- **移除**：`d-flex` 外层容器、`main-wrapper` 容器
- **添加**：`app-container` 外层容器、`sidebarOverlay` 移动端遮罩、`main-content` 主内容容器
- **替换**：`navbar` → `topNavbar`（使用新的公共片段）

### 2. 页面头部改造
- 在 content-area 顶部添加 `page-header` 容器
- 左侧：`page-title`（"用户管理"）+ 副标题描述
- 右侧：`page-actions`（将"新增用户"按钮移至此处）

### 3. 搜索/筛选区域
- 保留 card 包裹结构
- 添加 `card-glow` 效果增强视觉
- 添加 `animate-in delay-1` 入场动画
- 调整内部间距和样式，与设计系统对齐

### 4. 用户列表表格区域
- 保留 card 包裹结构
- 添加 `card-glow` 效果
- 添加 `animate-in delay-2` 入场动画
- 表格样式已由 components.css 覆盖，无需额外调整

### 5. 模态框（Modal）
- 新增用户模态框：保持结构不变，样式由 components.css 统一处理
- TOTP 设置模态框：保持结构不变

### 6. Toast 容器
- 移至使用公共片段 `toastContainer`（可选，或保留现有）

### 7. 保留的功能
- 所有 Thymeleaf 模板变量（`${pageTitle}` 等）
- 所有表单输入框 ID 和 onlick 事件
- 所有模态框 ID 和结构
- user-management.js 的所有依赖
- qrcodejs 库引用

## 四、文件修改清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `templates/pages/user-management.html` | 重写 | 应用新布局、新样式、入场动画 |

## 五、验证要点

1. ✅ 布局结构：app-container > sidebar + main-content > topNavbar + content-area
2. ✅ 页面头部：page-header + page-title 样式
3. ✅ 筛选区域：card 包裹，card-glow 效果
4. ✅ 表格区域：card 包裹，card-glow 效果
5. ✅ 入场动画：animate-in + delay 类
6. ✅ 样式基于 theme.css CSS 变量
7. ✅ 所有 Thymeleaf 变量和功能逻辑保留
