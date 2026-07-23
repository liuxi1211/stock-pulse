---
alwaysApply: false
description: "当用户涉及 JavaScript 编写、前端交互、DOM 操作、异步编程、ES6+ 语法、事件处理等场景时触发。适用于编写 JS 脚本、实现前端交互逻辑、处理用户事件、使用 JavaScript 新特性等任务。仅适用于 stock-watcher 前端 Thymeleaf 模板项目。关键词：JavaScript, JS, 前端, 交互, DOM, 异步, ES6, 事件, 脚本"
# JavaScript 代码风格规范

> 适用于 stock-watcher 前端 JavaScript 开发。
> 基于项目现有代码风格总结，与当前代码保持一致。

---

## 一、命名规范

### 1.1 变量与函数 ✅ MUST

- 使用 **小驼峰（camelCase）**
- 变量名有意义，不使用拼音或无意义缩写

```javascript
// 好的
const stockCode = '000001.SZ';
const closePrice = 10.5;
let klineData = [];

function getKlineData(code, period) {
    // ...
}

// 不好的 ❌
const gpdm = '000001.SZ';
const sp = 10.5;
let arr = [];

function getData(a, b) {
    // ...
}
```

### 1.2 常量 💡 SHOULD

- 全大写 + 下划线（UPPER_SNAKE_CASE）
- 使用 `const` 声明

```javascript
const MAX_RETRY_COUNT = 3;
const DEFAULT_PERIOD = 'daily';
const COLORS = {
    up: '#dc3545',
    down: '#198754'
};
```

> ⚠️ 业务常量（因子来源、universe、比较器、排序方向等）禁止硬编码字面量，必须集中定义并标注后端 Schema 来源。详见 [07-constants-usage.md](../java/08-constants-usage.md)。

### 1.3 类/构造函数 💡 SHOULD

- 大驼峰（PascalCase）
- 类名使用名词

```javascript
class SearchSuggest {
    constructor(input, options) {
        // ...
    }
}
```

### 1.4 布尔值 💡 SHOULD

- 前缀用 `is` / `has` / `can` / `should`

```javascript
const isLoading = true;
const hasData = false;
const canEdit = true;
```

---

## 二、变量声明

### 2.1 const / let / var ✅ MUST

- 优先使用 `const`，不会重新赋值的都用 `const`
- 需要重新赋值的用 `let`
- **禁止**使用 `var`

```javascript
// 好的
const list = [1, 2, 3];
let count = 0;
count++;

// 不好的 ❌
var name = 'test';
```

### 2.2 声明位置 💡 SHOULD

- 变量在作用域顶部声明
- 就近声明，在使用前声明
- 一个变量一个声明

```javascript
// 好的
function processData(data) {
    const result = [];
    let i = 0;

    for (i = 0; i < data.length; i++) {
        result.push(data[i] * 2);
    }

    return result;
}
```

---

## 三、函数规范

### 3.1 函数定义 ✅ MUST

- 优先使用函数声明或函数表达式
- 箭头函数用于回调或简短函数

```javascript
// 函数声明（推荐，有提升）
function calculateMA(data, period) {
    // ...
}

// 函数表达式
const calculateMACD = function(data) {
    // ...
};

// 箭头函数（回调）
data.map(item => item.close);
data.filter(item => item != null);
```

### 3.2 函数参数 💡 SHOULD

- 参数不超过 3-4 个，过多用对象
- 有默认值的参数放后面

```javascript
// 好的：参数多用对象
function initChart({ container, data, theme = 'light', height = 400 }) {
    // ...
}

// 调用
initChart({
    container: 'klineChart',
    data: klineData,
    height: 420
});
```

### 3.3 返回值 💡 SHOULD

- 函数有明确的返回值
- 提前 return 减少嵌套

```javascript
// 好的：提前返回
function getStockName(code) {
    if (!code) {
        return '';
    }
    const stock = stockMap.get(code);
    return stock ? stock.name : '';
}

// 不好的 ❌：嵌套过深
function getStockName(code) {
    if (code) {
        const stock = stockMap.get(code);
        if (stock) {
            return stock.name;
        } else {
            return '';
        }
    } else {
        return '';
    }
}
```

---

## 四、对象与数组

### 4.1 对象 💡 SHOULD

- 使用对象字面量
- 属性简写（变量名与属性名相同时）
- 方法简写

```javascript
// 属性简写
const name = 'test';
const obj = { name }; // 等同于 { name: name }

// 方法简写
const utils = {
    formatDate(date) {
        // ...
    }
};
```

### 4.2 数组 💡 SHOULD

- 使用数组字面量
- 善用数组方法：`map`、`filter`、`reduce`、`forEach`、`find`、`some`、`every`

```javascript
// 好的：使用数组方法
const names = stocks.map(s => s.name);
const activeStocks = stocks.filter(s => s.isActive);
const total = prices.reduce((sum, p) => sum + p, 0);

// 不好的 ❌：手写循环
const names = [];
for (let i = 0; i < stocks.length; i++) {
    names.push(stocks[i].name);
}
```

### 4.3 解构赋值 💡 SHOULD

```javascript
// 对象解构
const { code, name, price } = stock;

// 数组解构
const [first, second] = list;

// 函数参数解构
function renderStock({ code, name, change }) {
    // ...
}
```

---

## 五、字符串处理

### 5.1 模板字符串 ✅ MUST

- 使用反引号 `` ` `` 模板字符串
- 不用字符串拼接

```javascript
// 好的
const url = `/api/kline/${stockCode}?period=${period}`;
const message = `股票 ${name} 涨跌幅: ${change}%`;

// 不好的 ❌
const url = '/api/kline/' + stockCode + '?period=' + period;
const message = '股票 ' + name + ' 涨跌幅: ' + change + '%';
```

### 5.2 字符串方法 💡 SHOULD

```javascript
// 判空
if (!str || str.trim() === '') { ... }

// 包含
if (str.includes('keyword')) { ... }

// 开头/结尾
if (str.startsWith('http')) { ... }
if (str.endsWith('.json')) { ... }
```

---

## 六、条件与循环

### 6.1 相等比较 ✅ MUST

- 使用 `===` 和 `!==`，不用 `==` 和 `!=`
- 避免类型隐式转换导致的 bug

```javascript
// 好的
if (count === 0) { ... }
if (type === 'daily') { ... }

// 不好的 ❌
if (count == 0) { ... }
if (type == 'daily') { ... }
```

### 6.2 真值/假值判断 💡 SHOULD

```javascript
// 字符串判空
if (!str || str.trim() === '') { ... }

// 数组判空
if (!Array.isArray(list) || list.length === 0) { ... }

// 对象判空
if (!obj || Object.keys(obj).length === 0) { ... }
```

### 6.3 可选链 💡 SHOULD（现代浏览器支持）

```javascript
// 好的
const name = stock?.info?.name;

// 等价于
const name = stock && stock.info && stock.info.name;
```

---

## 七、异步编程

### 7.1 fetch API ✅ MUST

- 使用 fetch API 发起请求
- Promise `.then()` 链式调用
- 或 async/await（现代浏览器）

```javascript
// Promise 链式
fetch(url)
    .then(r => r.json())
    .then(data => {
        // 处理数据
    })
    .catch(err => {
        // 处理错误
    });

// async/await
async function fetchData() {
    try {
        const response = await fetch(url);
        const data = await response.json();
        return data;
    } catch (err) {
        console.error(err);
    }
}
```

### 7.2 错误处理 ✅ MUST

- 每个异步请求都要有错误处理
- 用户友好的错误提示（toast）

```javascript
StockApp.get(url, params, function(resp) {
    if (resp.code === 200) {
        // 成功
    } else {
        StockApp.toast(resp.message, 'warning');
    }
});
```

---

## 八、事件处理

### 8.1 事件绑定 💡 SHOULD

- 优先使用 `addEventListener`
- 内联事件（`onclick`）也可接受，简单场景

```javascript
// addEventListener
document.getElementById('btn').addEventListener('click', function(e) {
    // ...
});

// 内联事件（HTML 中）
<button onclick="handleClick()">点击</button>
```

### 8.2 事件委托 💡 SHOULD

动态生成的元素使用事件委托：

```javascript
document.getElementById('list').addEventListener('click', function(e) {
    if (e.target.classList.contains('item')) {
        // 处理点击
    }
});
```

---

## 九、模块化与命名空间

### 9.1 全局对象 ✅ MUST

- 全局功能挂载到 `StockApp` 对象上
- 避免污染全局命名空间
- 每个页面的独立脚本定义自己的对象

```javascript
// common.js - 公共工具
const StockApp = {
    contextPath: '',

    get(url, params, callback) { ... },
    post(url, body, callback) { ... },
    toast(message, type) { ... },
    // ...
};

// 页面脚本
const DashboardPage = {
    init() { ... },
    loadKline() { ... },
    // ...
};
```

### 9.2 脚本引入顺序 ✅ MUST

```html
<!-- 第三方库 -->
<script src="/js/bootstrap.bundle.min.js"></script>
<script src="/js/echarts.min.js"></script>

<!-- 公共脚本 -->
<script src="/js/common.js"></script>

<!-- 页面特定脚本 -->
<script src="/js/dashboard.js"></script>
```

---

## 十、DOM 操作

### 10.1 选择器 💡 SHOULD

- `document.getElementById()` - ID 选择（最快）
- `document.querySelector()` - CSS 选择器（单个）
- `document.querySelectorAll()` - CSS 选择器（多个）

```javascript
const el = document.getElementById('myId');
const items = document.querySelectorAll('.item');
```

### 10.2 创建/插入元素 💡 SHOULD

- 小量 DOM 操作直接操作
- 大量操作使用 `DocumentFragment` 或 `insertAdjacentHTML`
- 避免频繁操作 DOM（回流重绘）

```javascript
// 批量插入
const html = items.map(item => `
    <tr>
        <td>${item.name}</td>
        <td>${item.price}</td>
    </tr>
`).join('');
tbody.insertAdjacentHTML('beforeend', html);
```

### 10.3 XSS 防护 ✅ MUST

- 用户输入的内容必须转义后再插入 DOM
- 使用 `textContent` 而不是 `innerHTML` 插入文本
- 使用 `StockApp.escapeHtml()` 转义

```javascript
// 安全：使用 textContent
el.textContent = userInput;

// 安全：转义后再用 innerHTML
el.innerHTML = StockApp.escapeHtml(userInput);

// 危险 ❌：直接插入用户输入
el.innerHTML = userInput;
```

---

## 十一、项目特定规范

### 11.1 StockApp 公共对象 ✅ MUST

项目已提供 `StockApp` 全局对象，使用以下方法：

| 方法 | 用途 |
|-----|------|
| `StockApp.get(url, params, callback)` | GET 请求 |
| `StockApp.post(url, body, callback)` | POST 请求 |
| `StockApp.delete(url, callback)` | DELETE 请求 |
| `StockApp.toast(message, type)` | Toast 提示 |
| `StockApp.confirm(options)` | 确认对话框（Promise） |
| `StockApp.alert(options)` | 提示对话框（Promise） |
| `StockApp.escapeHtml(str)` | HTML 转义 |
| `StockApp.loadConstants(callback)` | 加载枚举常量 |
| `StockApp.getEnumLabel(enumName, code)` | 获取枚举标签 |

### 11.2 颜色规范 ✅ MUST

A股习惯：**涨红跌绿**

- 上涨/正数：红色（`#dc3545` / `text-danger`）
- 下跌/负数：绿色（`#198754` / `text-success`）

### 11.3 数字格式化 💡 SHOULD

- 价格保留 2 位小数
- 百分比保留 2 位小数
- 大数字加千分位
