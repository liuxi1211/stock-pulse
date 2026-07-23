---
alwaysApply: false
description: "当用户涉及前端性能优化、页面加载速度、DOM 优化、ECharts 渲染性能、资源加载、缓存策略、图片优化等前端性能场景时触发。适用于优化页面加载速度、提升渲染性能、减少内存占用、优化用户体验等任务。仅适用于 stock-watcher 前端项目。关键词：前端性能, 性能优化, 页面加载, DOM优化, 渲染性能, 缓存, 加载速度, 首屏"
# 前端性能优化规范

> 适用于 stock-watcher 前端性能优化。

---

## 一、资源加载优化

### 1.1 CSS 优化 ✅ MUST

- 使用 Bootstrap 5 压缩版
- 自定义 CSS 尽量少，优先用 Bootstrap 工具类
- CSS 放在 `<head>` 中

### 1.2 JS 优化 💡 SHOULD

- JS 放在 `<body>` 末尾，避免阻塞渲染
- 第三方库（Bootstrap、ECharts）使用压缩版（.min.js）
- 按页面按需加载，不使用的脚本不引入

```html
<!-- 好的：第三方库 + 公共脚本 + 页面脚本 -->
<body>
    ...
    <script src="/js/bootstrap.bundle.min.js"></script>
    <script src="/js/echarts.min.js"></script>
    <script src="/js/common.js"></script>
    <script src="/js/dashboard.js"></script>
</body>
```

### 1.3 字体图标 💡 SHOULD

- 使用 Bootstrap Icons 图标字体
- 不使用过大的图标库
- 只引入需要的图标（如可能）

---

## 二、渲染性能

### 2.1 DOM 操作优化 ✅ MUST

- 减少 DOM 操作次数
- 批量操作使用 `DocumentFragment` 或 `insertAdjacentHTML`
- 避免频繁操作 style，改用 class 切换

```javascript
// 不好的 ❌：频繁操作 DOM
const list = document.getElementById('list');
for (let i = 0; i < data.length; i++) {
    const li = document.createElement('li');
    li.textContent = data[i];
    list.appendChild(li); // 每次都触发回流
}

// 好的：批量插入
const html = data.map(item => `<li>${item}</li>`).join('');
list.insertAdjacentHTML('beforeend', html);
```

### 2.2 减少回流重绘 💡 SHOULD

- 避免频繁读取布局属性（offsetWidth、getBoundingClientRect 等）
- 使用 `classList` 切换样式，不要直接改 style
- 动画使用 `transform` 和 `opacity`，不触发回流

```css
/* 好的：使用 transform，只触发合成层 */
.element {
    transition: transform 0.3s;
}
.element:hover {
    transform: translateY(-2px);
}

/* 不好的 ❌：使用 margin-top，触发回流 */
.element {
    transition: margin-top 0.3s;
}
.element:hover {
    margin-top: -2px;
}
```

### 2.3 列表渲染优化 💡 SHOULD

- 长列表使用虚拟滚动（如数据量特别大）
- 表格大量数据时分页
- 避免嵌套过深的 DOM 结构

---

## 三、图片优化

### 3.1 图片格式 💡 SHOULD

- 图标使用 SVG 或字体图标
- 照片使用 WebP / JPEG
- PNG 用于透明图标

### 3.2 图片尺寸 💡 SHOULD

- 图片尺寸不超过显示尺寸的 2 倍
- 避免用 CSS 缩小大图片
- 列表小图用缩略图

### 3.3 懒加载 📌 MAY

- 首屏外的图片延迟加载
- 使用 `loading="lazy"` 属性

```html
<img src="image.jpg" alt="..." loading="lazy">
```

---

## 四、ECharts 图表性能

### 4.1 大数据量优化 ✅ MUST

- K线数据量大时使用 `dataZoom` 缩放
- 关闭不必要的动画：`animation: false`
- 折线图使用 `sampling: 'lttb'` 采样
- 不显示数据点：`symbol: 'none'`

```javascript
const option = {
    animation: false,
    dataZoom: [
        { type: 'inside', start: 0, end: 100 },
        { type: 'slider', start: 0, end: 100 }
    ],
    series: [{
        type: 'line',
        data: bigData,
        sampling: 'lttb',
        symbol: 'none'
    }]
};
```

### 4.2 避免频繁重绘 💡 SHOULD

- 数据更新使用增量 `setOption`
- 不频繁 `dispose` 再 `init`
- 切换数据时更新数据，不重建图表

### 4.3 及时销毁 ✅ MUST

- 页面切换/卸载时销毁图表
- 防止内存泄漏

```javascript
// 页面卸载时销毁
window.addEventListener('beforeunload', () => {
    if (myChart) myChart.dispose();
});
```

---

## 五、网络请求优化

### 5.1 减少请求次数 💡 SHOULD

- 合并请求：一次获取多个数据
- 批量操作：批量查询、批量提交
- 合理使用缓存

### 5.2 缓存策略 💡 SHOULD

- 不经常变的数据缓存到内存
- 枚举常量一次加载，缓存到 `StockApp._enumCache`
- K 线数据利用后端缓存

```javascript
// 已实现：枚举常量缓存
loadConstants(callback) {
    if (this._enumCache) {
        callback(this._enumCache);
        return;
    }
    this.get('/api/constants', null, resp => {
        if (resp.code === 200) {
            this._enumCache = resp.data;
        }
        callback(this._enumCache);
    });
}
```

### 5.3 请求防抖节流 💡 SHOULD

- 搜索输入框使用防抖
- 频繁触发的事件使用节流

```javascript
// 防抖函数
function debounce(fn, delay = 300) {
    let timer = null;
    return function(...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), delay);
    };
}

// 使用：搜索输入
const handleSearch = debounce(function(keyword) {
    // 发起搜索请求
}, 300);
```

---

## 六、JavaScript 性能

### 6.1 循环优化 💡 SHOULD

- 数组操作使用原生方法（map、filter、reduce）
- 大数据量避免使用 forEach（比 for 稍慢）
- 避免在循环中做昂贵的操作

```javascript
// 好的：使用数组方法
const names = stocks.map(s => s.name);
const actives = stocks.filter(s => s.isActive);

// 大数据量用 for 循环更快
let sum = 0;
for (let i = 0, len = arr.length; i < len; i++) {
    sum += arr[i];
}
```

### 6.2 内存管理 💡 SHOULD

- 及时清理不需要的引用
- 移除事件监听器
- 清除定时器
- 全局缓存设置上限

### 6.3 避免内存泄漏 ✅ MUST

- 定时器用完清除（`clearTimeout` / `clearInterval`）
- 事件监听器及时移除
- 闭包不持有大对象

---

## 七、首屏优化

### 7.1 关键路径渲染 💡 SHOULD

- 首屏内容优先渲染
- 非关键内容延迟加载
- 骨架屏或 loading 提示

### 7.2 页面加载指标 📌 MAY

| 指标 | 目标 |
|-----|------|
| 首屏绘制（FP） | < 1s |
| 首屏内容（FCP） | < 1.5s |
| 可交互（TTI） | < 3s |

---

## 八、性能监控与分析

### 8.1 开发者工具 💡 SHOULD

使用 Chrome DevTools 分析性能：

- **Performance** 面板：录制分析运行时性能
- **Memory** 面板：分析内存使用和泄漏
- **Network** 面板：分析网络请求
- **Lighthouse**：综合性能评估

### 8.2 关键指标监控 📌 MAY

- 页面加载时间
- API 请求耗时
- 图表渲染时间
- 错误率

---

## 九、项目特定优化点

### 9.1 K线页面 🔴 重点

- K线数据量大时必须优化
- 使用 dataZoom 避免一次渲染所有数据
- 图表切换时不销毁重建
- 均线等指标按需计算/加载

### 9.2 列表页面 🟡 关注

- 表格分页，不一次加载所有数据
- 搜索防抖
- 排序查询走数据库索引

### 9.3 仪表盘 🟡 关注

- 多个图表时注意初始性能
- 数据并行加载（可考虑）
- 缓存指数等高频数据
