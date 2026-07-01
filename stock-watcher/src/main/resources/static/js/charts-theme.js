/**
 * Charts Theme Manager
 * ECharts + Lightweight Charts 深色/浅色双主题适配
 */

const ChartsTheme = (function() {

    // ========== ECharts 主题配置 ==========
    function getEChartsTheme() {
        const isDark = document.documentElement.getAttribute('data-theme') === 'dark';

        if (isDark) {
            return {
                backgroundColor: 'transparent',
                textStyle: {
                    color: '#94a3b8',
                    fontFamily: "'Noto Sans SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
                },
                title: {
                    textStyle: { color: '#e2e8f0' },
                    subtextStyle: { color: '#64748b' }
                },
                legend: {
                    textStyle: { color: '#94a3b8' },
                    inactiveColor: '#475569'
                },
                tooltip: {
                    backgroundColor: 'rgba(21, 29, 43, 0.95)',
                    borderColor: 'rgba(59, 130, 246, 0.3)',
                    borderWidth: 1,
                    textStyle: { color: '#e2e8f0' },
                    extraCssText: 'backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); border-radius: 8px; box-shadow: 0 8px 32px rgba(0,0,0,0.3);'
                },
                axisLine: {
                    lineStyle: { color: '#1e293b' }
                },
                axisTick: {
                    lineStyle: { color: '#1e293b' }
                },
                axisLabel: {
                    color: '#64748b',
                    fontSize: 11
                },
                splitLine: {
                    lineStyle: { color: 'rgba(30, 41, 59, 0.6)', type: 'dashed' }
                },
                splitArea: {
                    areaStyle: { color: ['transparent', 'transparent'] }
                }
            };
        } else {
            return {
                backgroundColor: 'transparent',
                textStyle: {
                    color: '#64748b',
                    fontFamily: "'Noto Sans SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
                },
                title: {
                    textStyle: { color: '#1e293b' },
                    subtextStyle: { color: '#94a3b8' }
                },
                legend: {
                    textStyle: { color: '#64748b' },
                    inactiveColor: '#cbd5e1'
                },
                tooltip: {
                    backgroundColor: 'rgba(255, 255, 255, 0.95)',
                    borderColor: 'rgba(59, 130, 246, 0.2)',
                    borderWidth: 1,
                    textStyle: { color: '#1e293b' },
                    extraCssText: 'backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); border-radius: 8px; box-shadow: 0 8px 32px rgba(0,0,0,0.1);'
                },
                axisLine: {
                    lineStyle: { color: '#e2e8f0' }
                },
                axisTick: {
                    lineStyle: { color: '#e2e8f0' }
                },
                axisLabel: {
                    color: '#94a3b8',
                    fontSize: 11
                },
                splitLine: {
                    lineStyle: { color: 'rgba(226, 232, 240, 0.8)', type: 'dashed' }
                },
                splitArea: {
                    areaStyle: { color: ['transparent', 'transparent'] }
                }
            };
        }
    }

    // ========== Lightweight Charts 主题配置 ==========
    function getKlineTheme() {
        const isDark = document.documentElement.getAttribute('data-theme') === 'dark';

        if (isDark) {
            return {
                layout: {
                    background: { type: 'solid', color: 'transparent' },
                    textColor: '#94a3b8',
                    fontSize: 11,
                },
                grid: {
                    vertLines: { color: 'rgba(30, 41, 59, 0.6)', style: 2 },
                    horzLines: { color: 'rgba(30, 41, 59, 0.6)', style: 2 },
                },
                crosshair: {
                    mode: 0,
                    vertLine: {
                        color: 'rgba(59, 130, 246, 0.5)',
                        width: 1,
                        style: 2,
                        labelBackgroundColor: 'rgba(59, 130, 246, 0.8)',
                    },
                    horzLine: {
                        color: 'rgba(59, 130, 246, 0.5)',
                        width: 1,
                        style: 2,
                        labelBackgroundColor: 'rgba(59, 130, 246, 0.8)',
                    },
                },
                rightPriceScale: {
                    borderColor: 'rgba(30, 41, 59, 0.8)',
                    textColor: '#64748b',
                },
                timeScale: {
                    borderColor: 'rgba(30, 41, 59, 0.8)',
                    textColor: '#64748b',
                    timeVisible: false,
                    fixRightEdge: true,
                    rightOffset: 0,
                },
                candlestick: {
                    upColor: '#ef4444',
                    downColor: '#10b981',
                    borderUpColor: '#ef4444',
                    borderDownColor: '#10b981',
                    wickUpColor: '#ef4444',
                    wickDownColor: '#10b981',
                },
                histogram: {
                    upColor: 'rgba(239, 68, 68, 0.4)',
                    downColor: 'rgba(16, 185, 129, 0.4)',
                },
                volumeColor: {
                    up: 'rgba(239, 68, 68, 0.4)',
                    down: 'rgba(16, 185, 129, 0.4)',
                },
                maColors: {
                    ma5: '#3b82f6',
                    ma10: '#f97316',
                    ma20: '#a855f7',
                },
                legendColor: '#94a3b8',
                legendLabelColor: '#64748b',
            };
        } else {
            return {
                layout: {
                    background: { type: 'solid', color: 'transparent' },
                    textColor: '#64748b',
                    fontSize: 11,
                },
                grid: {
                    vertLines: { color: 'rgba(226, 232, 240, 0.8)', style: 2 },
                    horzLines: { color: 'rgba(226, 232, 240, 0.8)', style: 2 },
                },
                crosshair: {
                    mode: 0,
                    vertLine: {
                        color: 'rgba(59, 130, 246, 0.5)',
                        width: 1,
                        style: 2,
                        labelBackgroundColor: 'rgba(59, 130, 246, 0.9)',
                    },
                    horzLine: {
                        color: 'rgba(59, 130, 246, 0.5)',
                        width: 1,
                        style: 2,
                        labelBackgroundColor: 'rgba(59, 130, 246, 0.9)',
                    },
                },
                rightPriceScale: {
                    borderColor: 'rgba(226, 232, 240, 0.9)',
                    textColor: '#94a3b8',
                },
                timeScale: {
                    borderColor: 'rgba(226, 232, 240, 0.9)',
                    textColor: '#94a3b8',
                    timeVisible: false,
                    fixRightEdge: true,
                    rightOffset: 0,
                },
                candlestick: {
                    upColor: '#ef4444',
                    downColor: '#10b981',
                    borderUpColor: '#ef4444',
                    borderDownColor: '#10b981',
                    wickUpColor: '#ef4444',
                    wickDownColor: '#10b981',
                },
                histogram: {
                    upColor: 'rgba(239, 68, 68, 0.35)',
                    downColor: 'rgba(16, 185, 129, 0.35)',
                },
                volumeColor: {
                    up: 'rgba(239, 68, 68, 0.35)',
                    down: 'rgba(16, 185, 129, 0.35)',
                },
                maColors: {
                    ma5: '#2563eb',
                    ma10: '#ea580c',
                    ma20: '#9333ea',
                },
                legendColor: '#64748b',
                legendLabelColor: '#94a3b8',
            };
        }
    }

    // ========== ECharts 公共调色板 ==========
    function getChartColors() {
        return [
            '#3b82f6',
            '#8b5cf6',
            '#06b6d4',
            '#10b981',
            '#f59e0b',
            '#ef4444',
            '#ec4899',
            '#6366f1',
        ];
    }

    // ========== 柱形图渐变颜色 ==========
    function getBarGradient(chart, isPositive) {
        if (!chart) return isPositive ? '#ef4444' : '#10b981';
        const zr = chart.getZr();
        if (!zr) return isPositive ? '#ef4444' : '#10b981';

        if (isPositive) {
            return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(239, 68, 68, 0.9)' },
                { offset: 1, color: 'rgba(239, 68, 68, 0.2)' },
            ]);
        } else {
            return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(16, 185, 129, 0.9)' },
                { offset: 1, color: 'rgba(16, 185, 129, 0.2)' },
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

    return {
        getEChartsTheme,
        getKlineTheme,
        getChartColors,
        getBarGradient,
        getAreaGradient,
    };
})();
