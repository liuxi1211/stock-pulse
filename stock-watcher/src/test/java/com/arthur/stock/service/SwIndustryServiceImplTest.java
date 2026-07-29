package com.arthur.stock.service;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.SwIndustryConstants;
import com.arthur.stock.mapper.DailyQuoteMapper;
import com.arthur.stock.mapper.StockBasicMapper;
import com.arthur.stock.mapper.SwIndustryMapper;
import com.arthur.stock.mapper.SwIndustryMemberMapper;
import com.arthur.stock.model.DailyQuoteDO;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.model.StockBasicDO;
import com.arthur.stock.model.SwIndustryDO;
import com.arthur.stock.model.SwIndustryMemberDO;
import com.arthur.stock.service.impl.SwIndustryServiceImpl;
import com.arthur.stock.vo.IndustryMemberVO;
import com.arthur.stock.vo.IndustryRankingVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SwIndustryServiceImpl 纯单元测试（板块行情修复验证）。
 * <p>
 * 重点验证：
 * <ul>
 *   <li>{@code getIndustryRanking} 使用正确的 {@code src=SW2021} 查询（根因修复点）；</li>
 *   <li>分组聚合、领涨/领跌股排序、空数据兜底；</li>
 *   <li>{@code getIndustryMembers} 分页与 tradeDate 兜底。</li>
 * </ul>
 * <p>
 * 纯 Mockito，不启动 Spring、不连 DB（与 {@code ScreenerServiceImplTest} 约定一致）。
 */
@ExtendWith(MockitoExtension.class)
class SwIndustryServiceImplTest {

    @Mock
    private TushareClient tushareClient;
    @Mock
    private SwIndustryMapper swIndustryMapper;
    @Mock
    private SwIndustryMemberMapper swIndustryMemberMapper;
    @Mock
    private DailyQuoteMapper dailyQuoteMapper;
    @Mock
    private IndexDailyService indexDailyService;
    @Mock
    private StockBasicMapper stockBasicMapper;

    @InjectMocks
    private SwIndustryServiceImpl service;

    // ==================== getIndustryRanking ====================

    @Test
    void getIndustryRanking_使用SW2021作为src查询_修复SWS2021根因() {
        when(swIndustryMapper.selectByLevel(1, SwIndustryConstants.SW_SRC))
                .thenReturn(Arrays.asList(
                        SwIndustryDO.builder().indexCode("801010.SI").indexName("农林牧渔").level(1).src("SW2021").build(),
                        SwIndustryDO.builder().indexCode("801030.SI").indexName("化工").level(1).src("SW2021").build()));
        when(dailyQuoteMapper.selectLatestTradeDate()).thenReturn("20260728");
        when(swIndustryMemberMapper.selectAllCurrentL1Members(SwIndustryConstants.SW_SRC))
                .thenReturn(Collections.emptyList());

        List<IndustryRankingVO> result = service.getIndustryRanking(null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getIndustryCode()).isEqualTo("801010.SI");
        assertThat(result.get(0).getIndexCode()).isEqualTo("801010.SI");
        assertThat(result.get(0).getTradeDate()).isEqualTo("20260728");
        assertThat(result.get(0).getConstituentCount()).isZero();

        ArgumentCaptor<String> srcCaptor = ArgumentCaptor.forClass(String.class);
        verify(swIndustryMapper).selectByLevel(eq(1), srcCaptor.capture());
        assertThat(srcCaptor.getValue()).isEqualTo("SW2021");
    }

    @Test
    void getIndustryRanking_聚合领涨领跌股并按pctChg降序() {
        when(swIndustryMapper.selectByLevel(1, SwIndustryConstants.SW_SRC))
                .thenReturn(List.of(SwIndustryDO.builder().indexCode("801010.SI").indexName("农林牧渔").level(1).src("SW2021").build()));
        when(dailyQuoteMapper.selectLatestTradeDate()).thenReturn("20260728");

        SwIndustryMemberDO m1 = SwIndustryMemberDO.builder().tsCode("000001.SZ").indexCode("801010.SI").isNew(true).build();
        SwIndustryMemberDO m2 = SwIndustryMemberDO.builder().tsCode("000002.SZ").indexCode("801010.SI").isNew(true).build();
        SwIndustryMemberDO m3 = SwIndustryMemberDO.builder().tsCode("000003.SZ").indexCode("801010.SI").isNew(true).build();
        when(swIndustryMemberMapper.selectAllCurrentL1Members(SwIndustryConstants.SW_SRC))
                .thenReturn(Arrays.asList(m1, m2, m3));

        when(indexDailyService.getByCodesAndTradeDate(List.of("801010.SI"), "20260728"))
                .thenReturn(List.of(IndexDailyDO.builder().tsCode("801010.SI").tradeDate("20260728")
                        .pctChg(new BigDecimal("1.23")).amount(new BigDecimal("500")).build()));

        when(dailyQuoteMapper.selectByCodesAndTradeDate(anyList(), eq("20260728")))
                .thenReturn(Arrays.asList(
                        DailyQuoteDO.builder().tsCode("000001.SZ").pctChg(new BigDecimal("9.5")).build(),
                        DailyQuoteDO.builder().tsCode("000002.SZ").pctChg(new BigDecimal("2.0")).build(),
                        DailyQuoteDO.builder().tsCode("000003.SZ").pctChg(new BigDecimal("-5.5")).build()));
        when(stockBasicMapper.selectList(any()))
                .thenReturn(Arrays.asList(
                        StockBasicDO.builder().tsCode("000001.SZ").name("温氏股份").build(),
                        StockBasicDO.builder().tsCode("000002.SZ").name("万科A").build(),
                        StockBasicDO.builder().tsCode("000003.SZ").name("某跌停股").build()));

        List<IndustryRankingVO> result = service.getIndustryRanking(null);

        assertThat(result).hasSize(1);
        IndustryRankingVO vo = result.get(0);
        assertThat(vo.getConstituentCount()).isEqualTo(3);
        assertThat(vo.getActiveCount()).isEqualTo(3);
        assertThat(vo.getPctChg()).isEqualByComparingTo("1.23");
        assertThat(vo.getAmount()).isEqualByComparingTo("500");
        assertThat(vo.getTopGainerCode()).isEqualTo("000001.SZ");
        assertThat(vo.getTopGainerName()).isEqualTo("温氏股份");
        assertThat(vo.getTopGainerPctChg()).isEqualByComparingTo("9.5");
        assertThat(vo.getTopLoserCode()).isEqualTo("000003.SZ");
        assertThat(vo.getTopLoserPctChg()).isEqualByComparingTo("-5.5");
    }

    @Test
    void getIndustryRanking_无level1行业时返回空列表() {
        when(swIndustryMapper.selectByLevel(1, SwIndustryConstants.SW_SRC))
                .thenReturn(Collections.emptyList());

        List<IndustryRankingVO> result = service.getIndustryRanking(null);

        assertThat(result).isEmpty();
        verify(dailyQuoteMapper, never()).selectLatestTradeDate();
        verify(swIndustryMemberMapper, never()).selectAllCurrentL1Members(anyString());
    }

    @Test
    void getIndustryRanking_显式tradeDate透传不查最新交易日() {
        when(swIndustryMapper.selectByLevel(1, SwIndustryConstants.SW_SRC))
                .thenReturn(List.of(SwIndustryDO.builder().indexCode("801010.SI").indexName("农林牧渔").level(1).src("SW2021").build()));
        when(swIndustryMemberMapper.selectAllCurrentL1Members(SwIndustryConstants.SW_SRC))
                .thenReturn(Collections.emptyList());

        List<IndustryRankingVO> result = service.getIndustryRanking("20260101");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTradeDate()).isEqualTo("20260101");
        verify(dailyQuoteMapper, never()).selectLatestTradeDate();
    }

    // ==================== getIndustryMembers ====================

    @Test
    void getIndustryMembers_tradeDate为空时取最新交易日() {
        when(dailyQuoteMapper.selectLatestTradeDate()).thenReturn("20260728");
        when(dailyQuoteMapper.selectMembersWithQuote(eq("801010.SI"), eq("20260728"), isNull(), eq(20), eq(0)))
                .thenReturn(List.of(IndustryMemberVO.builder().tsCode("000001.SZ").name("温氏股份").build()));
        when(dailyQuoteMapper.countMembersWithQuote(eq("801010.SI"), eq("20260728"), isNull())).thenReturn(1L);

        var result = service.getIndustryMembers("801010.SI", null, 1, 20, null);

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
        verify(dailyQuoteMapper).selectLatestTradeDate();
    }

    @Test
    void getIndustryMembers_offset按页码计算且keyword透传() {
        when(dailyQuoteMapper.selectMembersWithQuote(eq("801010.SI"), eq("20260728"), eq("温氏"), eq(50), eq(50)))
                .thenReturn(Collections.emptyList());
        when(dailyQuoteMapper.countMembersWithQuote(eq("801010.SI"), eq("20260728"), eq("温氏"))).thenReturn(0L);

        var result = service.getIndustryMembers("801010.SI", "20260728", 2, 50, "温氏");

        assertThat(result.getList()).isEmpty();
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(50);
    }
}
