/**
 * ThemeManager —— 多主题切换管理
 *
 * 三主题:azure(云隙蔚蓝,默认) / mist(晨雾青瓷) / cyber(深空赛博)
 * 持久化:localStorage(sp-theme)
 * 交互:顶栏 #themeToggle 按钮,点击循环切换,图标随主题变
 *
 * 使用:
 *   - 页面加载时由 head 内联脚本(见 fragments/common.html)同步设置 data-theme,防首屏闪烁
 *   - DOM Ready 后调用 ThemeManager.init() 绑定切换按钮
 *   - 切换主题派发 window 的 'theme:changed' 事件,charts-theme.js 等模块监听后重绘
 */
const ThemeManager = (function () {

    const STORAGE_KEY = 'sp-theme';
    const THEMES = [
        { key: 'azure', name: '云隙蔚蓝', icon: 'bi-sun' },
        { key: 'mist',  name: '晨雾青瓷', icon: 'bi-droplet-half' },
        { key: 'cyber', name: '深空赛博', icon: 'bi-lightning-charge' },
    ];
    const DEFAULT_THEME = 'azure';

    function get() {
        return document.documentElement.getAttribute('data-theme') || DEFAULT_THEME;
    }

    function set(themeKey) {
        const idx = THEMES.findIndex(t => t.key === themeKey);
        if (idx === -1) themeKey = DEFAULT_THEME;
        document.documentElement.setAttribute('data-theme', themeKey);
        try { localStorage.setItem(STORAGE_KEY, themeKey); } catch (e) {}
        updateToggleIcon();
        window.dispatchEvent(new CustomEvent('theme:changed', { detail: { theme: themeKey } }));
    }

    function cycle() {
        const current = get();
        const idx = THEMES.findIndex(t => t.key === current);
        const next = THEMES[(idx + 1) % THEMES.length];
        set(next.key);
    }

    function updateToggleIcon() {
        const btn = document.getElementById('themeToggle');
        if (!btn) return;
        const current = get();
        const meta = THEMES.find(t => t.key === current) || THEMES[0];
        const iconEl = btn.querySelector('i');
        if (iconEl) {
            iconEl.className = 'bi ' + meta.icon;
        }
        btn.setAttribute('title', '切换主题(当前:' + meta.name + ')');
        btn.setAttribute('aria-label', '切换主题,当前' + meta.name);
    }

    function init() {
        const stored = (function () {
            try { return localStorage.getItem(STORAGE_KEY); } catch (e) { return null; }
        })();
        if (stored && THEMES.some(t => t.key === stored)) {
            document.documentElement.setAttribute('data-theme', stored);
        } else {
            document.documentElement.setAttribute('data-theme', DEFAULT_THEME);
        }
        updateToggleIcon();

        const btn = document.getElementById('themeToggle');
        if (btn && !btn._themeBound) {
            btn._themeBound = true;
            btn.addEventListener('click', cycle);
            btn.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    cycle();
                }
            });
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return { THEMES, get, set, cycle, init };
})();
