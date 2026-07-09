package com.arthur.stock.util;

import com.alibaba.fastjson2.JSON;
import com.arthur.stock.dto.strategy.StrategyDiffDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonDiffUtil 单元测试（spec 004 Task 16 / TR-6.7）。
 * <p>
 * 覆盖：added/removed/modified、嵌套 Map 递归、List 按索引比较 + 长度差异、
 * 类型不同（Map vs List / 原始值）、null 处理、path 格��（点号 + [n]）、Number 值相等（1 == 1.0）。
 * <p>
 * 用 fastjson2 {@code JSON.parseObject} 解析成 Map 后调 {@link JsonDiffUtil#diff(Object, Object)}，
 * 或直接用 {@link JsonDiffUtil#diff(String, String)}。
 */
class JsonDiffUtilTest {

    // ==================== 基础 added/removed/modified ====================

    /** TR-6.7-a：to 独有 key → added。 */
    @Test
    void diff_to独有key_应为added() {
        Map<String, Object> from = JSON.parseObject("{\"a\":1}");
        Map<String, Object> to = JSON.parseObject("{\"a\":1,\"b\":2}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getPath()).isEqualTo("b");
        assertThat(diffs.get(0).getChangeType()).isEqualTo("added");
        assertThat(diffs.get(0).getOldValue()).isNull();
        assertThat(diffs.get(0).getNewValue()).isEqualTo(2);
    }

    /** TR-6.7-b：from 独有 key → removed。 */
    @Test
    void diff_from独有key_应为removed() {
        Map<String, Object> from = JSON.parseObject("{\"a\":1,\"b\":2}");
        Map<String, Object> to = JSON.parseObject("{\"a\":1}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getPath()).isEqualTo("b");
        assertThat(diffs.get(0).getChangeType()).isEqualTo("removed");
        assertThat(diffs.get(0).getOldValue()).isEqualTo(2);
        assertThat(diffs.get(0).getNewValue()).isNull();
    }

    /** TR-6.7-c：同 key 值不同 → modified。 */
    @Test
    void diff_同key值不同_应为modified() {
        Map<String, Object> from = JSON.parseObject("{\"a\":1}");
        Map<String, Object> to = JSON.parseObject("{\"a\":2}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(1);
        StrategyDiffDTO d = diffs.get(0);
        assertThat(d.getPath()).isEqualTo("a");
        assertThat(d.getChangeType()).isEqualTo("modified");
        assertThat(d.getOldValue()).isEqualTo(1);
        assertThat(d.getNewValue()).isEqualTo(2);
    }

    /** TR-6.7-d：完全相同 → 空列表。 */
    @Test
    void diff_完全相同_应返回空列表() {
        Map<String, Object> from = JSON.parseObject("{\"a\":1,\"b\":\"x\"}");
        Map<String, Object> to = JSON.parseObject("{\"a\":1,\"b\":\"x\"}");

        assertThat(JsonDiffUtil.diff(from, to)).isEmpty();
    }

    // ==================== 嵌套 Map 递归 ====================

    /** TR-6.7-e：嵌套 Map 递归 + path 用点号。 */
    @Test
    void diff_嵌套Map_应递归并生成点号路径() {
        Map<String, Object> from = JSON.parseObject(
                "{\"trading_config\":{\"position_sizing\":{\"target\":0.95}}}");
        Map<String, Object> to = JSON.parseObject(
                "{\"trading_config\":{\"position_sizing\":{\"target\":0.8}}}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getPath()).isEqualTo("trading_config.position_sizing.target");
        assertThat(diffs.get(0).getChangeType()).isEqualTo("modified");
    }

    // ==================== List 按索引比较 ====================

    /** TR-6.7-f：List 同长度按索引逐一比较（顺序变化但同索引值相同 → 无 modified）。 */
    @Test
    void diff_同长度List_应按索引逐一比较() {
        Map<String, Object> from = JSON.parseObject("{\"symbols\":[\"000001.SZ\",\"600000.SH\"]}");
        Map<String, Object> to = JSON.parseObject("{\"symbols\":[\"000001.SZ\",\"600000.SH\"]}");

        assertThat(JsonDiffUtil.diff(from, to)).isEmpty();
    }

    /** TR-6.7-g：List 同长度但同索引元素不同 → modified，path 带 [n]。 */
    @Test
    void diff_同长度List元素不同_应按索引modified() {
        Map<String, Object> from = JSON.parseObject("{\"symbols\":[\"A\",\"B\"]}");
        Map<String, Object> to = JSON.parseObject("{\"symbols\":[\"A\",\"C\"]}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getPath()).isEqualTo("symbols[1]");
        assertThat(diffs.get(0).getChangeType()).isEqualTo("modified");
        assertThat(diffs.get(0).getOldValue()).isEqualTo("B");
        assertThat(diffs.get(0).getNewValue()).isEqualTo("C");
    }

    /** TR-6.7-h：to 比 from 长 → 尾部 added。 */
    @Test
    void diff_List尾部新增_尾部应为added() {
        Map<String, Object> from = JSON.parseObject("{\"symbols\":[\"A\"]}");
        Map<String, Object> to = JSON.parseObject("{\"symbols\":[\"A\",\"B\",\"C\"]}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(2);
        assertThat(diffs).allMatch(d -> "added".equals(d.getChangeType()));
        assertThat(diffs).extracting(StrategyDiffDTO::getPath)
                .containsExactlyInAnyOrder("symbols[1]", "symbols[2]");
    }

    /** TR-6.7-i：from 比 to 长 → 尾部 removed。 */
    @Test
    void diff_List尾部删减_尾部应为removed() {
        Map<String, Object> from = JSON.parseObject("{\"symbols\":[\"A\",\"B\",\"C\"]}");
        Map<String, Object> to = JSON.parseObject("{\"symbols\":[\"A\"]}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(2);
        assertThat(diffs).allMatch(d -> "removed".equals(d.getChangeType()));
        assertThat(diffs).extracting(StrategyDiffDTO::getPath)
                .containsExactlyInAnyOrder("symbols[1]", "symbols[2]");
    }

    /** TR-6.7-j：List 元素是 Map，递归进 Map 比较，path 形如 conditions[0].left.factor。 */
    @Test
    void diff_List元素为Map_应递归进Map并生成复合路径() {
        Map<String, Object> from = JSON.parseObject(
                "{\"conditions\":[{\"left\":{\"factor\":\"CLOSE\"}},{\"operator\":\">\"}]}");
        Map<String, Object> to = JSON.parseObject(
                "{\"conditions\":[{\"left\":{\"factor\":\"OPEN\"}},{\"operator\":\">\"}]}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getPath()).isEqualTo("conditions[0].left.factor");
        assertThat(diffs.get(0).getChangeType()).isEqualTo("modified");
        assertThat(diffs.get(0).getOldValue()).isEqualTo("CLOSE");
        assertThat(diffs.get(0).getNewValue()).isEqualTo("OPEN");
    }

    // ==================== 类型不同 ====================

    /** TR-6.7-k：同 key 一边 Map 一边原始值 → modified（含 oldValue/newValue）。 */
    @Test
    void diff_同key类型不同MapVs原始_应为modified() {
        Map<String, Object> from = JSON.parseObject("{\"a\":{\"x\":1}}");
        Map<String, Object> to = JSON.parseObject("{\"a\":1}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(1);
        StrategyDiffDTO d = diffs.get(0);
        assertThat(d.getPath()).isEqualTo("a");
        assertThat(d.getChangeType()).isEqualTo("modified");
        assertThat(d.getOldValue()).isInstanceOf(Map.class);
        assertThat(d.getNewValue()).isEqualTo(1);
    }

    /** TR-6.7-l：同 key 一边 List 一边 Map → modified。 */
    @Test
    void diff_同key类型不同ListVsMap_应为modified() {
        Map<String, Object> from = JSON.parseObject("{\"a\":[1,2]}");
        Map<String, Object> to = JSON.parseObject("{\"a\":{\"x\":1}}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getPath()).isEqualTo("a");
        assertThat(diffs.get(0).getChangeType()).isEqualTo("modified");
    }

    // ==================== null 处理 ====================

    /** TR-6.7-m：from=null, to=非null → added（注意：顶层 path 为 ""）。 */
    @Test
    void diff_fromNull_应为added() {
        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff((Object) null, Map.of("a", 1));

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getChangeType()).isEqualTo("added");
        assertThat(diffs.get(0).getNewValue()).isEqualTo(Map.of("a", 1));
    }

    /** TR-6.7-n：from=非null, to=null → removed。 */
    @Test
    void diff_toNull_应为removed() {
        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(Map.of("a", 1), (Object) null);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getChangeType()).isEqualTo("removed");
        assertThat(diffs.get(0).getOldValue()).isEqualTo(Map.of("a", 1));
    }

    /** TR-6.7-o：子 key 值从有到 null → 整体 modified（按 from=非null to=null 视为 removed 分支）。
     *  实现细节：null 走早退 removed 分支。这里覆盖 Map 内 value=null。 */
    @Test
    void diff_Map内值变为null_应removed() {
        Map<String, Object> from = JSON.parseObject("{\"a\":{\"x\":1}}");
        Map<String, Object> to = JSON.parseObject("{\"a\":null}");

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getPath()).isEqualTo("a");
        assertThat(diffs.get(0).getChangeType()).isEqualTo("removed");
    }

    // ==================== Number 值相等（1 == 1.0）====================

    /** TR-6.7-p：Number 做值比较，1 与 1.0 视为相等 → 无 diff。 */
    @Test
    void diff_Number值相等_1与1点0应无差异() {
        Map<String, Object> from = JSON.parseObject("{\"target\":1}");
        Map<String, Object> to = JSON.parseObject("{\"target\":1.0}");

        assertThat(JsonDiffUtil.diff(from, to)).isEmpty();
    }

    // ==================== 字符串入口 + 空串/null ====================

    /** TR-6.7-q：String 入口 + 空串视为 null。 */
    @Test
    void diff_字符串入口空串_应解析为null处理() {
        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff("", "{\"a\":1}");

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getChangeType()).isEqualTo("added");
    }

    /** TR-6.7-r：两侧都 null → 空列表。 */
    @Test
    void diff_两侧都null_应返回空列表() {
        assertThat(JsonDiffUtil.diff((Object) null, null)).isEmpty();
    }

    /** TR-6.7-s：策略配置典型场景（buy conditions + sizing target 同变）。
     *  验证多路径同时输出，按 Map 遍历顺序自然排列。 */
    @Test
    void diff_策略配置典型场景_应输出多条变更() {
        String from = "{\"trading_config\":{\"symbols\":[\"A\"],\"position_sizing\":{\"target\":0.95}}}";
        String to = "{\"trading_config\":{\"symbols\":[\"A\",\"B\"],\"position_sizing\":{\"target\":0.8}}}";

        List<StrategyDiffDTO> diffs = JsonDiffUtil.diff(from, to);

        assertThat(diffs).hasSize(2);
        assertThat(diffs).anyMatch(d ->
                "trading_config.symbols[1]".equals(d.getPath()) && "added".equals(d.getChangeType()));
        assertThat(diffs).anyMatch(d ->
                "trading_config.position_sizing.target".equals(d.getPath())
                        && "modified".equals(d.getChangeType()));
    }
}
