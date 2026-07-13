/**
 * Charts Theme Manager
 * ECharts + Lightweight Charts 主题跟随 CSS 变量
 *
 * 工作方式:
 *  - 所有图表颜色运行时读取 :root 的 --chart-* / --accent-* / --rise-* / --fall-* 变量
 *  - 页面创建图表实例后调用 ChartsTheme.register(instance, type) 注册
 *  - 主题切换时 ThemeManager 派发 theme:changed 事件,本模块自动重绘所有已注册实例
 *  - 页面也可手动调用 ChartsTheme.applyTheme() 强制刷新
 */
const ChartsTheme = (function () {

    const STORAGE_KEY = 'sp-theme';

    // 已注册图表实例:type === 'echarts' 用 setOption;type === 'lightweight' 用 applyOptions
    const registry = []; // { instance, type, repaint: Function }

    function readVar(name) {
        const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
        return v;
    }

    function readVarWith(name, fallback) {
        const v = readVar(name);
        return v || fallback;
    }

    // ========== ECharts 主题配置(读 CSS 变量) ==========
    function getEChartsTheme() {
        return {
            backgroundColor: 'transparent',
            textStyle: {
                color: readVar('--chart-text') || '#94a3b8',
                fontFamily: "'Noto Sans SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
            },
            title: {
                textStyle: { color: readVar('--text-primary') || '#e2e8f0' },
                subtextStyle: { color: readVar('--chart-axis') || '#64748b' }
            },
            legend: {
                textStyle: { color: readVar('--chart-text') || '#94a3b8' },
                inactiveColor: readVar('--chart-axis') || '#475569'
            },
            tooltip: {
                backgroundColor: readVar('--chart-tooltip-bg') || 'rgba(21, 29, 43, 0.95)',
                borderColor: readVar('--chart-tooltip-border') || 'rgba(59, 130, 246, 0.3)',
                borderWidth: 1,
                textStyle: { color: readVar('--chart-tooltip-text') || '#e2e8f0' },
                extraCssText: 'backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); border-radius: 8px; box-shadow: 0 8px 32px rgba(0,0,0,0.2);'
            },
            axisLine: {
                lineStyle: { color: readVar('--chart-grid') || '#1e293b' }
            },
            axisTick: {
                lineStyle: { color: readVar('--chart-grid') || '#1e293b' }
            },
            axisLabel: {
                color: readVar('--chart-axis') || '#64748b',
                fontSize: 11
            },
            splitLine: {
                lineStyle: { color: readVar('--chart-split-line') || 'rgba(30, 41, 59, 0.6)', type: 'dashed' }
            },
            splitArea: {
                areaStyle: { color: ['transparent', 'transparent'] }
            }
        };
    }

    // ========== Lightweight Charts 主题配置(读 CSS 变量) ==========
    function getKlineTheme() {
        const riseColor = readVar('--rise-color') || '#ef4444';
        const fallColor = readVar('--fall-color') || '#10b981';
        return {
            layout: {
                background: { type: 'solid', color: 'transparent' },
                textColor: readVar('--chart-text') || '#94a3b8',
                fontSize: 11,
            },
            grid: {
                vertLines: { color: readVar('--chart-split-line') || 'rgba(30, 41, 59, 0.6)', style: 2 },
                horzLines: { color: readVar('--chart-split-line') || 'rgba(30, 41, 59, 0.6)', style: 2 },
            },
            crosshair: {
                mode: 0,
                vertLine: {
                    color: readVar('--chart-crosshair') || 'rgba(59, 130, 246, 0.5)',
                    width: 1,
                    style: 2,
                    labelBackgroundColor: readVar('--chart-crosshair-label') || 'rgba(59, 130, 246, 0.8)',
                },
                horzLine: {
                    color: readVar('--chart-crosshair') || 'rgba(59, 130, 246, 0.5)',
                    width: 1,
                    style: 2,
                    labelBackgroundColor: readVar('--chart-crosshair-label') || 'rgba(59, 130, 246, 0.8)',
                },
            },
            rightPriceScale: {
                borderColor: readVar('--chart-grid') || 'rgba(30, 41, 59, 0.8)',
                textColor: readVar('--chart-axis') || '#64748b',
            },
            timeScale: {
                borderColor: readVar('--chart-grid') || 'rgba(30, 41, 59, 0.8)',
                textColor: readVar('--chart-axis') || '#64748b',
                timeVisible: false,
                fixRightEdge: true,
                rightOffset: 0,
            },
            candlestick: {
                upColor: riseColor,
                downColor: fallColor,
                borderUpColor: riseColor,
                borderDownColor: fallColor,
                wickUpColor: riseColor,
                wickDownColor: fallColor,
            },
            histogram: {
                upColor: hexToRgba(riseColor, 0.4),
                downColor: hexToRgba(fallColor, 0.4),
            },
            volumeColor: {
                up: hexToRgba(riseColor, 0.4),
                down: hexToRgba(fallColor, 0.4),
            },
            maColors: {
                ma5: readVar('--accent-blue') || '#3b82f6',
                ma10: readVar('--accent-orange') || '#f97316',
                ma20: readVar('--accent-purple') || '#a855f7',
            },
            legendColor: readVar('--chart-text') || '#94a3b8',
            legendLabelColor: readVar('--chart-axis') || '#64748b',
        };
    }

    // ========== ECharts 公共调色板(读 CSS 变量) ==========
    function getChartColors() {
        return [
            readVarWith('--accent-blue', '#3b82f6'),
            readVarWith('--accent-purple', '#8b5cf6'),
            readVarWith('--accent-cyan', '#06b6d4'),
            readVarWith('--accent-green', '#10b981'),
            readVarWith('--accent-yellow', '#f59e0b'),
            readVarWith('--rise-color', '#ef4444'),
            readVarWith('--accent-purple-light', '#ec4899'),
            readVarWith('--accent-blue-light', '#6366f1'),
        ].filter(Boolean);
    }

    // ========== 柱形图渐变颜色(读 CSS 变量) ==========
    function getBarGradient(chart, isPositive) {
        if (!chart) return isPositive ? readVar('--rise-color') || '#ef4444' : readVar('--fall-color') || '#10b981';
        const zr = chart.getZr();
        if (!zr) return isPositive ? readVar('--rise-color') || '#ef4444' : readVar('--fall-color') || '#10b981';

        const riseColor = readVar('--rise-color') || '#ef4444';
        const fallColor = readVar('--fall-color') || '#10b981';

        if (isPositive) {
            return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: hexToRgba(riseColor, 0.9) },
                { offset: 1, color: hexToRgba(riseColor, 0.2) },
            ]);
        } else {
            return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: hexToRgba(fallColor, 0.9) },
                { offset: 1, color: hexToRgba(fallColor, 0.2) },
            ]);
        }
    }

    // ========== 折线图渐变面积 ==========
    function getAreaGradient(chart, color) {
        if (!chart) return color;
        return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: color + '30' },
            { offset: 1, color: color + '00' },
        ]);
    }

    // ========== 颜色工具:hex(#rrggbb) → rgba 字符串 ==========
    function hexToRgba(hex, alpha) {
        if (!hex) return `rgba(0,0,0,${alpha})`;
        hex = hex.trim();
        if (hex.startsWith('rgba') || hex.startsWith('rgb')) return hex;
        if (hex.startsWith('rgb(')) {
            return hex.replace('rgb(', 'rgba(').replace(')', `, ${alpha})`);
        }
        const m = hex.replace('#', '');
        const full = m.length === 3 ? m.split('').map(c => c + c).join('') : m;
        if (full.length !== 6) return `rgba(0,0,0,${alpha})`;
        const r = parseInt(full.slice(0, 2), 16);
        const g = parseInt(full.slice(2, 4), 16);
        const b = parseInt(full.slice(4, 6), 16);
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }

    // ========== 实例注册 ==========
    function register(instance, type, repaint) {
        if (!instance) return;
        type = type || detectType(instance);
        const entry = { instance, type, repaint: repaint || defaultRepaint };
        registry.push(entry);
        return instance;
    }

    function detectType(instance) {
        if (typeof echarts !== 'undefined' && instance && typeof instance.getZr === 'function') return 'echarts';
        if (instance && typeof instance.applyOptions === 'function') return 'lightweight';
        return 'echarts';
    }

    function defaultRepaint(entry) {
        try {
            if (entry.type === 'lightweight') {
                const t = getKlineTheme();
                entry.instance.applyOptions({
                    layout: t.layout,
                    grid: t.grid,
                    crosshair: t.crosshair,
                    rightPriceScale: t.rightPriceScale,
                    timeScale: t.timeScale,
                });
            } else {
                entry.instance.setOption(getEChartsTheme(), true);
            }
        } catch (e) {
            console.warn('[ChartsTheme] repaint failed', e);
        }
    }

    // ========== 重绘所有已注册实例(主题切换时调用) ==========
    function applyTheme() {
        registry.forEach(defaultRepaint);
    }

    // ========== 监听主题切换事件,自动重绘 ==========
    window.addEventListener('theme:changed', function () {
        setTimeout(applyTheme, 60);
    });

    return {
        getEChartsTheme,
        getKlineTheme,
        getChartColors,
        getBarGradient,
        getAreaGradient,
        register,
        applyTheme,
    };
})();
