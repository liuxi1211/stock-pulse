package com.arthur.stock.dto.screener;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证选股 DTO 经 fastjson2 {@code JSON.toJSONString} 序列化后，多词字段输出 snake_case，
 * 与 engine Pydantic Schema（{@code top_n}/{@code exclude_st}/{@code ohlcv_history}/{@code is_st} 等）对齐。
 * <p>
 * 修复背景：watcher→engine 全链路零 camelCase↔snake_case 转换，Pydantic v2 默认严格按字段名匹配，
 * 未匹配字段静默忽略用默认值 → topN/filters/candidates.meta 静默失效。
 */
class ScreenerDtoSerializationTest {

    @Test
    void snapshotRequest_topNAndVerboseExcluded_serializeToSnakeCase() {
        SnapshotRequestDTO dto = new SnapshotRequestDTO();
        dto.setUniverse("csi300");
        dto.setDate("2026-07-03");
        dto.setTopN(30);
        dto.setVerboseExcluded(true);

        JSONObject json = JSON.parseObject(JSON.toJSONString(dto));

        assertThat(json).containsKey("top_n").containsKey("verbose_excluded");
        assertThat(json.getInteger("top_n")).isEqualTo(30);
        assertThat(json.getBoolean("verbose_excluded")).isTrue();
        assertThat(json).doesNotContainKey("topN").doesNotContainKey("verboseExcluded");
    }

    @Test
    void filters_allMultiWordFields_serializeToSnakeCase() {
        FiltersDTO f = new FiltersDTO();
        f.setExcludeSt(true);
        f.setExcludeSuspended(false);
        f.setExcludeLimitUp(true);
        f.setExcludeLimitDown(false);
        f.setIndustries(Collections.singletonList("银行"));
        f.setExcludeIndustries(Collections.singletonList("房地产"));
        f.setMinListDays(60);

        JSONObject json = JSON.parseObject(JSON.toJSONString(f));

        assertThat(json).containsKeys(
                "exclude_st", "exclude_suspended", "exclude_limit_up", "exclude_limit_down",
                "exclude_industries", "min_list_days", "industries");
        assertThat(json).doesNotContainKeys(
                "excludeSt", "excludeSuspended", "excludeLimitUp", "excludeLimitDown",
                "excludeIndustries", "minListDays");
        assertThat(json.getBoolean("exclude_st")).isTrue();
        assertThat(json.getInteger("min_list_days")).isEqualTo(60);
    }

    @Test
    void candidateStock_ohlcvHistory_serializeToSnakeCase() {
        CandidateStockDTO c = new CandidateStockDTO();
        OhlcvBarDTO bar = new OhlcvBarDTO();
        bar.setDate("20260703");
        bar.setOpen(new BigDecimal("10.0"));
        bar.setHigh(new BigDecimal("10.1"));
        bar.setLow(new BigDecimal("9.9"));
        bar.setClose(new BigDecimal("10.05"));
        bar.setVolume(new BigDecimal("100000"));
        c.setOhlcvHistory(List.of(bar));
        Map<String, BigDecimal> fund = new HashMap<>();
        fund.put("PE_TTM", new BigDecimal("28.5"));
        c.setFundamentals(fund);

        JSONObject json = JSON.parseObject(JSON.toJSONString(c));

        assertThat(json).containsKey("ohlcv_history").doesNotContainKey("ohlcvHistory");
        assertThat(json.getJSONArray("ohlcv_history")).hasSize(1);
        assertThat(json.getJSONObject("fundamentals").get("PE_TTM")).isNotNull();
    }

    @Test
    void candidateMeta_allMultiWordFields_serializeToSnakeCase() {
        CandidateMetaDTO m = new CandidateMetaDTO();
        m.setIsSt(true);
        m.setIsSuspended(false);
        m.setIsLimitUp(false);
        m.setIsLimitDown(false);
        m.setIndustry("综合");
        m.setListDate("2020-01-01");

        JSONObject json = JSON.parseObject(JSON.toJSONString(m));

        assertThat(json).containsKeys("is_st", "is_suspended", "is_limit_up", "is_limit_down", "list_date", "industry");
        assertThat(json).doesNotContainKeys("isSt", "isSuspended", "isLimitUp", "isLimitDown", "listDate");
        assertThat(json.getBoolean("is_st")).isTrue();
        assertThat(json.getString("list_date")).isEqualTo("2020-01-01");
    }
}
