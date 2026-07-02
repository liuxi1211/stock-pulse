/**
 * SearchSuggest - 通用搜索提示组件
 * 可绑定到任意 <input> 元素，输入时显示匹配建议下拉列表
 *
 * 用法:
 *   new SearchSuggest(document.getElementById('myInput'), {
 *       onSelect: function(item) { console.log(item.code, item.name); }
 *   });
 */
class SearchSuggest {

    /**
     * @param {HTMLInputElement} inputEl - 目标输入框元素
     * @param {Object} options - 配置项
     * @param {Function} [options.onSelect] - 选中回调，参数 {code, name}
     * @param {string} [options.url] - 自定义建议接口地址
     * @param {number} [options.debounceMs=300] - 防抖延迟（毫秒）
     * @param {number} [options.minLength=1] - 触发搜索的最小输入长度
     */
    constructor(inputEl, options = {}) {
        if (!inputEl) return;

        this.input = inputEl;
        this.onSelect = options.onSelect || null;
        this.url = options.url || '/search/suggest';
        this.debounceMs = options.debounceMs || 300;
        this.minLength = options.minLength || 1;

        this._timer = null;
        this._index = -1;
        this._items = [];

        this._createDropdown();
        this._bindEvents();
    }

    _createDropdown() {
        this.dropdown = document.createElement('div');
        this.dropdown.className = 'search-suggest-dropdown';
        this.dropdown.innerHTML = '<div class="search-suggest-empty">请输入关键字</div>';
        this.input.parentElement.style.position = 'relative';
        this.input.parentElement.appendChild(this.dropdown);
    }

    _bindEvents() {
        this.input.addEventListener('input', () => this._onInput());
        this.input.addEventListener('keydown', (e) => this._onKeydown(e));
        this.input.addEventListener('focus', () => { if (this._items.length) this._show(); });

        document.addEventListener('click', (e) => {
            if (!this.dropdown.contains(e.target) && e.target !== this.input) {
                this._hide();
            }
        });
    }

    _onInput() {
        clearTimeout(this._timer);
        const keyword = this.input.value.trim();

        if (keyword.length < this.minLength) {
            this._items = [];
            this._renderEmpty('请输入关键字');
            return;
        }

        this._timer = setTimeout(() => this._fetch(keyword), this.debounceMs);
    }

    _onKeydown(e) {
        if (!this.dropdown.classList.contains('show')) return;

        const list = this.dropdown.querySelectorAll('.search-suggest-item');
        if (!list.length) return;

        if (e.key === 'ArrowDown') {
            e.preventDefault();
            this._index = Math.min(this._index + 1, list.length - 1);
            this._highlight(list);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            this._index = Math.max(this._index - 1, 0);
            this._highlight(list);
        } else if (e.key === 'Enter') {
            if (this._index >= 0 && this._index < this._items.length) {
                e.preventDefault();
                this._select(this._items[this._index]);
            }
        } else if (e.key === 'Escape') {
            this._hide();
        }
    }

    _fetch(keyword) {
        StockApp.get(this.url, { keyword: keyword }, (resp) => {
            if (resp.code !== 200) return;
            this._items = resp.data || [];
            this._index = -1;
            if (this._items.length) {
                this._renderItems();
            } else {
                this._renderEmpty('无匹配结果');
            }
            this._show();
        });
    }

    _renderItems() {
        this.dropdown.innerHTML = this._items.map((item, i) =>
            `<div class="search-suggest-item" data-index="${i}">` +
            `<span class="search-suggest-code">${StockApp.escapeHtml(item.code)}</span>` +
            `<span class="search-suggest-name">${StockApp.escapeHtml(item.name)}</span>` +
            `</div>`
        ).join('');

        this.dropdown.querySelectorAll('.search-suggest-item').forEach(el => {
            el.addEventListener('click', () => {
                const idx = parseInt(el.dataset.index);
                this._select(this._items[idx]);
            });
            el.addEventListener('mouseenter', () => {
                this._index = parseInt(el.dataset.index);
                this._highlight(this.dropdown.querySelectorAll('.search-suggest-item'));
            });
        });
    }

    _renderEmpty(msg) {
        this.dropdown.innerHTML = `<div class="search-suggest-empty">${msg}</div>`;
        if (msg === '请输入关键字') {
            this._hide();
        }
    }

    _highlight(list) {
        list.forEach((el, i) => el.classList.toggle('active', i === this._index));
    }

    _select(item) {
        this.input.value = item.code;
        this._hide();
        if (this.onSelect) this.onSelect(item);
    }

    _show() {
        this.dropdown.classList.add('show');
    }

    _hide() {
        this.dropdown.classList.remove('show');
        this._index = -1;
    }

    destroy() {
        clearTimeout(this._timer);
        this.dropdown.remove();
    }
}
