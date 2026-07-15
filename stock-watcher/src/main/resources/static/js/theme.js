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

    function applyTheme(themeKey) {
        const idx = THEMES.findIndex(t => t.key === themeKey);
        if (idx === -1) themeKey = DEFAULT_THEME;
        document.documentElement.setAttribute('data-theme', themeKey);
        document.documentElement.style.colorScheme = (themeKey === 'cyber') ? 'dark' : 'light';
        return themeKey;
    }

    function set(themeKey) {
        themeKey = applyTheme(themeKey);

        // 持久化:写入后立即回读校验,失败时输出诊断信息
        try {
            localStorage.setItem(STORAGE_KEY, themeKey);
            const verify = localStorage.getItem(STORAGE_KEY);
            if (verify !== themeKey) {
                console.warn('[ThemeManager] localStorage 写入校验失败:期望', themeKey, '实际', verify);
            }
        } catch (e) {
            console.warn('[ThemeManager] localStorage 不可用,主题无法持久化:', e);
        }

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

    function readPersistedTheme() {
        try {
            const stored = localStorage.getItem(STORAGE_KEY);
            return (stored && THEMES.some(t => t.key === stored)) ? stored : DEFAULT_THEME;
        } catch (e) {
            return DEFAULT_THEME;
        }
    }

    function init() {
        // 始终强制把 data-theme 设为持久化值。
        // 不能省略这一步:某些浏览器扩展(暗色模式/翻译插件)会在页面加载后
        // 把 data-theme 篡改成 light/dark,必须在此纠正回来,否则 CSS 失配导致主题还原。
        applyTheme(readPersistedTheme());
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

    // 监听 <html> data-theme 被外部(浏览器扩展等)篡改为非法值时自动纠正。
    // 合法值仅 azure/mist/cyber;若被改成 light/dark 等则恢复为持久化主题。
    function installGuard() {
        const observer = new MutationObserver(function (mutations) {
            for (const m of mutations) {
                if (m.attributeName !== 'data-theme') continue;
                const v = document.documentElement.getAttribute('data-theme');
                if (!THEMES.some(t => t.key === v)) {
                    applyTheme(readPersistedTheme());
                    updateToggleIcon();
                }
            }
        });
        observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { init(); installGuard(); });
    } else {
        init();
        installGuard();
    }

    return { THEMES, get, set, cycle, init };
})();
