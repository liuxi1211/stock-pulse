/* ============================================================
   StockPulse Theme Manager
   主题切换管理器 - 深色/浅色主题
   ============================================================ */

const ThemeManager = (function() {
    const STORAGE_KEY = 'stockpulse_theme';
    const root = document.documentElement;
    let transitionTimer = null;

    function init() {
        const savedTheme = localStorage.getItem(STORAGE_KEY);
        const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        const theme = savedTheme || (prefersDark ? 'dark' : 'light');
        applyTheme(theme, false);
    }

    function applyTheme(theme, animate = true) {
        if (animate) {
            root.classList.add('theme-transition');
            if (transitionTimer) clearTimeout(transitionTimer);
            transitionTimer = setTimeout(() => {
                root.classList.remove('theme-transition');
            }, 400);
        }

        root.setAttribute('data-theme', theme);
        localStorage.setItem(STORAGE_KEY, theme);

        updateThemeToggleIcons();

        document.dispatchEvent(new CustomEvent('themechange', {
            detail: { theme: theme, isDark: theme === 'dark' }
        }));
    }

    function toggleTheme() {
        const current = root.getAttribute('data-theme') || 'dark';
        const next = current === 'dark' ? 'light' : 'dark';
        applyTheme(next, true);
        return next;
    }

    function getTheme() {
        return root.getAttribute('data-theme') || 'dark';
    }

    function isDark() {
        return getTheme() === 'dark';
    }

    function updateThemeToggleIcons() {
        const isDarkTheme = isDark();
        const iconClass = isDarkTheme ? 'bi bi-moon-stars' : 'bi bi-sun';

        const icons = document.querySelectorAll('[id$="ThemeIcon"], [id$="themeIcon"]');
        icons.forEach(icon => {
            icon.className = iconClass;
        });

        const toggleBtns = document.querySelectorAll('[id$="ToggleBtn"] [id$="Icon"], [id$="toggleBtn"] [id$="Icon"]');
        toggleBtns.forEach(icon => {
            icon.className = iconClass;
        });
    }

    document.addEventListener('DOMContentLoaded', init);

    if (document.readyState !== 'loading') {
        init();
    }

    return {
        init,
        toggleTheme,
        applyTheme,
        getTheme,
        isDark
    };
})();

function toggleTheme() {
    return ThemeManager.toggleTheme();
}
