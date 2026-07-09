package com.arthur.stock.constant;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.arthur.stock.constant.StrategyStatusEnum.ACTIVE;
import static com.arthur.stock.constant.StrategyStatusEnum.ARCHIVED;
import static com.arthur.stock.constant.StrategyStatusEnum.DRAFT;
import static com.arthur.stock.constant.StrategyStatusEnum.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策略状态机单元测试（spec 004 Task 16 / TR-6.6）。
 * <p>
 * 覆盖 {@link StrategyStatusEnum#canTransitionTo} 与 {@link StrategyStatusEnum#allowedTransitions()}，
 * 以及 {@link StrategyStatusEnum#fromCode(String)} 反查。
 * <p>
 * 合法流转（spec FR-2）：
 * <pre>
 *   DRAFT ──▶ VERIFIED ──▶ ACTIVE ──▶ ARCHIVED
 *     ▲          │
 *     └──────────┘ (VERIFIED 可退回 DRAFT)
 * </pre>
 * ARCHIVED 为终态。
 */
class StrategyStatusEnumTest {

    // ==================== canTransitionTo 合法流转 ====================

    @Test
    void DRAFT到VERIFIED_应合法() {
        assertThat(DRAFT.canTransitionTo(VERIFIED)).isTrue();
    }

    @Test
    void VERIFIED到DRAFT_应合法() {
        assertThat(VERIFIED.canTransitionTo(DRAFT)).isTrue();
    }

    @Test
    void VERIFIED到ACTIVE_应合法() {
        assertThat(VERIFIED.canTransitionTo(ACTIVE)).isTrue();
    }

    @Test
    void ACTIVE到ARCHIVED_应合法() {
        assertThat(ACTIVE.canTransitionTo(ARCHIVED)).isTrue();
    }

    /** 自身视为合法（保留当前状态）。 */
    @Test
    void 同状态_应合法() {
        for (StrategyStatusEnum s : StrategyStatusEnum.values()) {
            assertThat(s.canTransitionTo(s)).isTrue();
        }
    }

    /** target=null 视为合法（防御性）。 */
    @Test
    void target为null_应返回true() {
        assertThat(DRAFT.canTransitionTo(null)).isTrue();
    }

    // ==================== canTransitionTo 非法流转 ====================

    /** DRAFT 直接跳 ACTIVE（跳过 VERIFIED）→ 非法。 */
    @Test
    void DRAFT到ACTIVE_应非法() {
        assertThat(DRAFT.canTransitionTo(ACTIVE)).isFalse();
    }

    /** DRAFT 直接跳 ARCHIVED → 非法。 */
    @Test
    void DRAFT到ARCHIVED_应非法() {
        assertThat(DRAFT.canTransitionTo(ARCHIVED)).isFalse();
    }

    /** VERIFIED 直接跳 ARCHIVED → 非法。 */
    @Test
    void VERIFIED到ARCHIVED_应非法() {
        assertThat(VERIFIED.canTransitionTo(ARCHIVED)).isFalse();
    }

    /** ACTIVE 回退 DRAFT → 非法。 */
    @Test
    void ACTIVE到DRAFT_应非法() {
        assertThat(ACTIVE.canTransitionTo(DRAFT)).isFalse();
    }

    /** ACTIVE 回退 VERIFIED → 非法。 */
    @Test
    void ACTIVE到VERIFIED_应非法() {
        assertThat(ACTIVE.canTransitionTo(VERIFIED)).isFalse();
    }

    /** ARCHIVED 为终态，任何流转都非法（不含自身）。 */
    @Test
    void ARCHIVED到任意非自身状态_都应非法() {
        assertThat(ARCHIVED.canTransitionTo(DRAFT)).isFalse();
        assertThat(ARCHIVED.canTransitionTo(VERIFIED)).isFalse();
        assertThat(ARCHIVED.canTransitionTo(ACTIVE)).isFalse();
    }

    // ==================== allowedTransitions ====================

    @Test
    void allowedTransitions_DRAFT_应含自身与VERIFIED() {
        Set<StrategyStatusEnum> s = DRAFT.allowedTransitions();
        assertThat(s).containsExactlyInAnyOrder(DRAFT, VERIFIED);
    }

    @Test
    void allowedTransitions_VERIFIED_应含自身DRAFT与ACTIVE() {
        Set<StrategyStatusEnum> s = VERIFIED.allowedTransitions();
        assertThat(s).containsExactlyInAnyOrder(DRAFT, VERIFIED, ACTIVE);
    }

    @Test
    void allowedTransitions_ACTIVE_应含自身与ARCHIVED() {
        Set<StrategyStatusEnum> s = ACTIVE.allowedTransitions();
        assertThat(s).containsExactlyInAnyOrder(ACTIVE, ARCHIVED);
    }

    @Test
    void allowedTransitions_ARCHIVED_应仅含自身() {
        assertThat(ARCHIVED.allowedTransitions()).containsExactly(ARCHIVED);
    }

    // ==================== fromCode ====================

    @Test
    void fromCode_合法code_应反查到对应枚举() {
        assertThat(StrategyStatusEnum.fromCode("DRAFT")).isEqualTo(DRAFT);
        assertThat(StrategyStatusEnum.fromCode("VERIFIED")).isEqualTo(VERIFIED);
        assertThat(StrategyStatusEnum.fromCode("ACTIVE")).isEqualTo(ACTIVE);
        assertThat(StrategyStatusEnum.fromCode("ARCHIVED")).isEqualTo(ARCHIVED);
    }

    @Test
    void fromCode_未知code_应返回null() {
        assertThat(StrategyStatusEnum.fromCode("UNKNOWN")).isNull();
    }

    @Test
    void fromCode_null_应返回null() {
        assertThat(StrategyStatusEnum.fromCode(null)).isNull();
    }

    // ==================== 元数据 ====================

    @Test
    void code与label_应符合预期() {
        assertThat(DRAFT.getCode()).isEqualTo("DRAFT");
        assertThat(DRAFT.getLabel()).isEqualTo("草稿");
        assertThat(VERIFIED.getCode()).isEqualTo("VERIFIED");
        assertThat(ACTIVE.getCode()).isEqualTo("ACTIVE");
        assertThat(ARCHIVED.getCode()).isEqualTo("ARCHIVED");
    }
}
