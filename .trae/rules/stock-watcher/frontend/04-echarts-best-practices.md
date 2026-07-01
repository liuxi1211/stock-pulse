---
alwaysApply: false
description: "当用户涉及 ECharts 图表开发、数据可视化、图表配置、K线图、折线图、柱状图、饼图、图表性能优化等场景时触发。适用于开发数据图表、配置 ECharts 选项、优化图表渲染性能、实现交互效果等任务。仅适用于 stock-watcher 前端项目。关键词：ECharts, 图表, 可视化, K线图, 折线图, 柱状图, 数据可视化, 图表配置"
# ECharts 最佳实践

> 适用于 stock-watcher 前端 ECharts 图表开发。

---

## 一、基本使用

### 1.1 引入 ECharts ✅ MUST

- 使用本地 ECharts 文件，不依赖 CDN
- 放在 `static/js/` 目录
- 页面使用前确保已加载

```html
<script src="/js/echarts.min.js"></script>
```

### 1.2 初始化图表 💡 SHOULD

```javascript
// 获取容器
const chartDom = document.getElementById('klineChart');

// 初始化
const myChart = echarts.init(chartDom);

// 设置配置项
const option = {
    // ... 配置
};
myChart.setOption(option);

// 响应窗口大小变化
window.addEventListener('resize', () => {
    myChart.resize();
});
```

### 1.3 容器高度 ✅ MUST

- 图表容器必须有明确的高度
- 不要用百分比高度（父元素没有高度时会失效）
- 使用固定像素高度或 flex 布局

```html
<div id="klineChart" style="height: 420px;"></div>
```

---

## 二、配置项规范

### 2.1 配置项结构 💡 SHOULD

```javascript
const option = {
    title: { ... },
    tooltip: { ... },
    legend: { ... },
    grid: { ... },
    xAxis: { ... },
    yAxis: { ... },
    series: [ ... ]
};
```

### 2.2 通用配置 💡 SHOULD

```javascript
const option = {
    // 边距
    grid: {
        left: 60,
        right: 20,
        top: 30,
        bottom: 30
    },

    // 提示框
    tooltip: {
        trigger: 'axis',
        axisPointer: {
            type: 'cross'
        }
    },

    // 动画
    animation: true,
    animationDuration: 500
};
```

---

## 三、K 线图规范

### 3.1 K 线数据格式 ✅ MUST

```javascript
// K线数据格式：[日期, 开盘, 收盘, 最低, 最高, 成交量]
const klineData = [
    ['2024-01-01', 10.00, 10.50, 9.80, 10.60, 1000000],
    ['2024-01-02', 10.50, 10.20, 10.10, 10.80, 1200000],
    // ...
];
```

### 3.2 K 线配置 💡 SHOULD

```javascript
const option = {
    tooltip: {
        trigger: 'axis',
        axisPointer: {
            type: 'cross'
        },
        formatter: function(params) {
            const data = params[0].data;
            return `
                <div>日期: ${data[0]}</div>
                <div>开盘: ${data[1]}</div>
                <div>收盘: ${data[2]}</div>
                <div>最低: ${data[3]}</div>
                <div>最高: ${data[4]}</div>
                <div>成交量: ${data[5]}</div>
            `;
        }
    },
    xAxis: {
        type: 'category',
        data: klineData.map(item => item[0]),
        scale: true
    },
    yAxis: {
        scale: true,
        splitLine: {
            lineStyle: {
                type: 'dashed'
            }
        }
    },
    series: [
        {
            name: 'K线',
            type: 'candlestick',
            data: klineData.map(item => [item[1], item[2], item[3], item[4]]),
            itemStyle: {
                color: '#dc3545',       // 阳线红
                color0: '#198754',      // 阴线绿
                borderColor: '#dc3545',
                borderColor0: '#198754'
            }
        }
    ]
};
```

### 3.3 涨红跌绿 ✅ MUST

A股习惯：**涨红跌绿**

```javascript
itemStyle: {
    color: '#dc3545',       // 阳线（涨）红色
    color0: '#198754',      // 阴线（跌）绿色
    borderColor: '#dc3545',
    borderColor0: '#198754'
}
```

---

## 四、折线图规范

### 4.1 折线图配置 💡 SHOULD

```javascript
const option = {
    tooltip: {
        trigger: 'axis'
    },
    legend: {
        data: ['MA5', 'MA10', 'MA20']
    },
    xAxis: {
        type: 'category',
        data: dates
    },
    yAxis: {
        type: 'value',
        scale: true
    },
    series: [
        {
            name: 'MA5',
            type: 'line',
            data: ma5Data,
            smooth: false,
            lineStyle: {
                width: 1
            },
            symbol: 'none'
        },
        // ...
    ]
};
```

---

## 五、柱状图规范

### 5.1 成交量柱状图 💡 SHOULD

```javascript
const option = {
    xAxis: {
        type: 'category',
        data: dates
    },
    yAxis: {
        type: 'value'
    },
    series: [
        {
            name: '成交量',
            type: 'bar',
            data: volumeData.map((vol, i) => ({
                value: vol,
                itemStyle: {
                    color: closeData[i] >= openData[i] ? '#dc3545' : '#198754'
                }
            }))
        }
    ]
};
```

---

## 六、性能优化

### 6.1 大数据量优化 ✅ MUST

K线数据量大时（>1000 条）：

1. **使用 dataZoom**：数据缩放，只渲染可视区域
2. **关闭动画**：`animation: false`
3. **采样**：`sampling: 'lttb'` 大采样
4. **symbol: 'none'**：不显示数据点

```javascript
const option = {
    animation: false,
    dataZoom: [
        {
            type: 'inside',
            start: 0,
            end: 100
        },
        {
            type: 'slider',
            start: 0,
            end: 100
        }
    ],
    series: [
        {
            type: 'line',
            data: bigData,
            sampling: 'lttb',
            symbol: 'none'
        }
    ]
};
```

### 6.2 避免频繁重绘 💡 SHOULD

- 数据更新使用 `setOption` 增量更新
- 不要每次都重新 `init`
- 批量更新数据，减少 `setOption` 调用次数

```javascript
// 好的：增量更新
myChart.setOption({
    series: [{ data: newData }]
});

// 不好的 ❌：每次重新初始化
const myChart = echarts.init(dom);
myChart.setOption(option);
```

### 6.3 图表销毁 ✅ MUST

页面卸载或切换时销毁图表，防止内存泄漏：

```javascript
// 页面卸载时销毁
window.addEventListener('beforeunload', () => {
    if (myChart) {
        myChart.dispose();
    }
});

// 或使用 MutationObserver 监听元素移除
```

---

## 七、主题与样式

### 7.1 颜色规范 💡 SHOULD

**涨跌色（A股）：**

| 含义 | 颜色 | 色值 |
|-----|------|------|
| 涨 / 阳线 | 红色 | `#dc3545` |
| 跌 / 阴线 | 绿色 | `#198754` |

**指标线色：**

| 指标 | 颜色 | 色值 |
|-----|------|------|
| MA5 | 黄色 | `#ffc107` |
| MA10 | 紫色 | `#6f42c1` |
| MA20 | 蓝色 | `#0d6efd` |
| MA60 | 青色 | `#0dcaf0` |

### 7.2 统一主题 💡 SHOULD

- 全站图表风格保持一致
- 可抽取公共配置函数

```javascript
// 公共配置
function getCommonOption() {
    return {
        grid: { left: 60, right: 20, top: 30, bottom: 30 },
        tooltip: { trigger: 'axis' },
        // ...
    };
}

// 使用
const option = getCommonOption();
option.xAxis = { ... };
option.series = [ ... ];
```

---

## 八、交互体验

### 8.1 数据加载中 💡 SHOULD

```javascript
// 显示加载动画
myChart.showLoading();

// 数据加载完成后隐藏
myChart.hideLoading();
myChart.setOption(option);
```

### 8.2 空数据提示 💡 SHOULD

```javascript
if (!data || data.length === 0) {
    myChart.setOption({
        title: {
            text: '暂无数据',
            left: 'center',
            top: 'center',
            textStyle: {
                color: '#999',
                fontSize: 14
            }
        }
    });
    return;
}
```

### 8.3 自适应窗口大小 ✅ MUST

```javascript
window.addEventListener('resize', () => {
    myChart.resize();
});
```

---

## 九、常用图表类型

| 图表类型 | series.type | 适用场景 |
|---------|-------------|---------|
| K线图 | `candlestick` | 股票K线 |
| 折线图 | `line` | 均线、趋势线 |
| 柱状图 | `bar` | 成交量、成交额 |
| 饼图 | `pie` | 占比分析 |
| 散点图 | `scatter` | 分布分析 |
| 雷达图 | `radar` | 多维度对比 |
