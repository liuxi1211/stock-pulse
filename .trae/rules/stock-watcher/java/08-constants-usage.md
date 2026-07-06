---
alwaysApply: false
description: "当用户涉及常量组、枚举、魔法值、下拉选项、状态码、来源标识、universe 候选池、排序方向、比较器、因子来源等场景时触发。适用于 stock-watcher Java 后端定义常量/枚举，以及前端 Thymeleaf/JS 引用业务常量的任务。关键词：常量, 枚举, 魔法值, 下拉, 选项, universe, source, dataSource, comparator, ranking, AKQUANT, TUSHARE, DisplayableEnum, /constants"
# 常量与枚举使用规范

> 明确常量与常量组的定义位置、形态和使用方式，避免魔法值散落。

---

## 一、两类常量

| 类型 | 形态 | 定义位置 | 命名 | 示例 |
|---|---|---|---|---|
| **常量** | 类中 `static final` 字段 | `com.arthur.stock.constant.*` | 全大写下划线 | `SessionKeys.AUTH_USER` |
| **常量组** | `enum` 枚举 | `com.arthur.stock.constant.*Enum` | 枚举名大驼峰，值全大写 | `BoardEnum.MAIN` |

---

## 二、常量：类中定义，全大写

- 单一或少量无关联的字面量，直接定义在常量类中。
- 类名为大驼峰，字段 `static final`，名称为 `UPPER_SNAKE_CASE`。
- 构造器私有，禁止实例化。

```java
public final class SessionKeys {
    public static final String AUTH_USER = "AUTH_USER";
    public static final String TOTP_VERIFIED = "TOTP_VERIFIED";

    private SessionKeys() {}
}
```

---

## 三、常量组：必须定义成 DisplayableEnum 枚举

```java
package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum BoardEnum implements DisplayableEnum {

    MAIN("主板", "主板"),
    GEM("创业板", "创业板"),
    STAR("科创板", "科创板"),
    CDR("CDR", "CDR"),
    BSE("北交所", "北交所");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    BoardEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static BoardEnum fromCode(String code) {
        if (code == null) return null;
        for (BoardEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
```

- 所有常量组统一实现 `DisplayableEnum`。
- `code`：存储/传输值；`label`：中文展示名。
- 必须提供 `fromCode(String)`。
- `ConstantController` 启动时自动扫描 `com.arthur.stock.constant` + `com.arthur.stock.model`，通过 `GET /constants` 暴露为 `Map<类名, List<{code, label}>>`。

前端用法：

```javascript
StockApp.loadConstants(function(enums) {
    const opts = enums.BoardEnum.map(o => `<option value="${o.code}">${o.label}</option>`).join('');
});

StockApp.getEnumLabel('BoardEnum', '创业板'); // → '创业板'
```

---

## 四、engine 侧 Schema 字面量

engine Python Schema 中的 `Literal[...]` / 固定字符串（如 `source`、`comparator`、`universe`、`ranking.method`）没有 Java 枚举对应，由 watcher 透传到前端。**前端必须在自己的 JS 顶部集中维护为常量表**。

```javascript
const FACTOR_SOURCES = ['AKQUANT', 'TUSHARE', 'RAW', 'DERIVED'];
const COMPARATORS = ['>', '<', '>=', '<=', '==', '!='];
const RANKING_METHODS = { SINGLE: 'single', COMPOSITE: 'composite' };
const UNIVERSES = [
    { code: 'all_a_shares', label: '全部 A 股' },
    { code: 'csi300',       label: '沪深 300' },
];
```

---

## 五、禁止事项

- ❌ 禁止在业务代码中写裸字符串/数字魔法值。
- ❌ 禁止前端硬编码 A 类枚举的 `code` / `label`。
- ❌ 禁止手写 `<option>` 静态枚举值；必须从 `/constants` 或前端常量表动态生成。
- ❌ 禁止同一语义在 Java 枚举和前端同时维护两份字面量。

---

## 六、提交前自查

- [ ] 新增常量组时，必须定义为 `DisplayableEnum` 枚举。
- [ ] 新增 `DisplayableEnum` 时，同时提供 `fromCode(String)`。
- [ ] 改动 engine `Literal[...]` 时，同步前端对应常量表。
- [ ] 没有引入新的裸字面量（除非在常量/枚举定义处）。
