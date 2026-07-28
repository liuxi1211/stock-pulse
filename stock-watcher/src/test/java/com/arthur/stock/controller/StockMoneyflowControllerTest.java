package com.arthur.stock.controller;

import com.arthur.stock.constant.SessionKeys;
import com.arthur.stock.dto.BlockTradeWithCloseVO;
import com.arthur.stock.exception.GlobalExceptionHandler;
import com.arthur.stock.model.HkHoldDO;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.model.TopInstDO;
import com.arthur.stock.model.TopListDO;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.BlockTradeService;
import com.arthur.stock.service.HkHoldService;
import com.arthur.stock.service.MoneyflowService;
import com.arthur.stock.service.TopListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockMoneyflowController.class)
@Import({GlobalExceptionHandler.class, StockMoneyflowControllerTest.TestConfig.class})
class StockMoneyflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MoneyflowService moneyflowService;
    @Autowired
    private HkHoldService hkHoldService;
    @Autowired
    private TopListService topListService;
    @Autowired
    private BlockTradeService blockTradeService;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(moneyflowService, hkHoldService, topListService, blockTradeService);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean MoneyflowService moneyflowService() { return Mockito.mock(MoneyflowService.class); }
        @Bean HkHoldService hkHoldService() { return Mockito.mock(HkHoldService.class); }
        @Bean TopListService topListService() { return Mockito.mock(TopListService.class); }
        @Bean BlockTradeService blockTradeService() { return Mockito.mock(BlockTradeService.class); }
    }

    // ==================== moneyflows ====================

    @Test
    void moneyflows_正常返回数据和元信息() throws Exception {
        when(moneyflowService.queryDetail("000001.SZ", 30)).thenReturn(List.of(
                MoneyflowDO.builder().tsCode("000001.SZ").tradeDate("20250701").build(),
                MoneyflowDO.builder().tsCode("000001.SZ").tradeDate("20250702").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/moneyflows", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tradeDate").value("20250701"))
                .andExpect(jsonPath("$.data.items[1].tradeDate").value("20250702"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250702"))
                .andExpect(jsonPath("$.data.meta.limit").value(30));

        verify(moneyflowService).queryDetail("000001.SZ", 30);
    }

    @Test
    void moneyflows_空数据返回稳定空列表() throws Exception {
        when(moneyflowService.queryDetail(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/moneyflows", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== hk-holds ====================

    @Test
    void hkHolds_3M范围正常返回() throws Exception {
        LocalDate today = LocalDate.now();
        String startDate = today.minusMonths(3).format(DateTimeFormatter.BASIC_ISO_DATE);
        String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        when(hkHoldService.queryByCodeAndDateRange("000001.SZ", startDate, endDate)).thenReturn(List.of(
                HkHoldDO.builder().tsCode("000001.SZ").tradeDate("20250701").build(),
                HkHoldDO.builder().tsCode("000001.SZ").tradeDate("20250702").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/hk-holds", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tradeDate").value("20250701"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250702"))
                .andExpect(jsonPath("$.data.meta.startDate").value(startDate))
                .andExpect(jsonPath("$.data.meta.endDate").value(endDate));

        verify(hkHoldService).queryByCodeAndDateRange("000001.SZ", startDate, endDate);
    }

    @Test
    void hkHolds_自定义日期范围() throws Exception {
        when(hkHoldService.queryByCodeAndDateRange("000001.SZ", "20250101", "20250601")).thenReturn(List.of(
                HkHoldDO.builder().tsCode("000001.SZ").tradeDate("20250115").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/hk-holds", "000001.SZ")
                        .param("startDate", "20250101")
                        .param("endDate", "20250601"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tradeDate").value("20250115"))
                .andExpect(jsonPath("$.data.meta.startDate").value("20250101"))
                .andExpect(jsonPath("$.data.meta.endDate").value("20250601"));

        verify(hkHoldService).queryByCodeAndDateRange("000001.SZ", "20250101", "20250601");
    }

    // ==================== top-lists ====================

    @Test
    void topLists_正常返回倒序数据() throws Exception {
        LocalDate today = LocalDate.now();
        String startDate = today.minusYears(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        when(topListService.queryByCodeAndDateRange("000001.SZ", startDate, endDate)).thenReturn(List.of(
                TopListDO.builder().tsCode("000001.SZ").tradeDate("20250702").build(),
                TopListDO.builder().tsCode("000001.SZ").tradeDate("20250601").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/top-lists", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tradeDate").value("20250702"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250702"))
                .andExpect(jsonPath("$.data.meta.limit").value(100));

        verify(topListService).queryByCodeAndDateRange("000001.SZ", startDate, endDate);
    }

    @Test
    void topLists_空数据返回稳定空列表() throws Exception {
        when(topListService.queryByCodeAndDateRange(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/top-lists", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== top-lists seats ====================

    @Test
    void topListSeats_正常返回席位明细() throws Exception {
        when(topListService.queryInst("20250702", "000001.SZ")).thenReturn(List.of(
                TopInstDO.builder().tsCode("000001.SZ").tradeDate("20250702").exalter("营业部A").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/top-lists/{tradeDate}/seats",
                        "000001.SZ", "20250702"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].exalter").value("营业部A"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250702"));

        verify(topListService).queryInst("20250702", "000001.SZ");
    }

    // ==================== block-trades ====================

    @Test
    void blockTrades_正常返回并计算溢价率() throws Exception {
        LocalDate today = LocalDate.now();
        String startDate = today.minusMonths(3).format(DateTimeFormatter.BASIC_ISO_DATE);
        String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        BlockTradeWithCloseVO vo = new BlockTradeWithCloseVO();
        vo.setTsCode("000001.SZ");
        vo.setTradeDate("20250702");
        vo.setPrice(new BigDecimal("11.00"));
        vo.setVol(new BigDecimal("100"));
        vo.setAmount(new BigDecimal("1100"));
        vo.setClosePrice(new BigDecimal("10.00"));
        when(blockTradeService.queryByCodeAndDateRange("000001.SZ", startDate, endDate))
                .thenReturn(List.of(vo));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/block-trades", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tradeDate").value("20250702"))
                .andExpect(jsonPath("$.data.items[0].premiumRate").value(10.0000))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.page").value(1))
                .andExpect(jsonPath("$.data.meta.size").value(20))
                .andExpect(jsonPath("$.data.meta.total").value(1));

        verify(blockTradeService).queryByCodeAndDateRange("000001.SZ", startDate, endDate);
    }

    @Test
    void blockTrades_缺收盘价时溢价率为null() throws Exception {
        BlockTradeWithCloseVO vo = new BlockTradeWithCloseVO();
        vo.setTsCode("000001.SZ");
        vo.setTradeDate("20250702");
        vo.setPrice(new BigDecimal("11.00"));
        vo.setVol(new BigDecimal("100"));
        vo.setAmount(new BigDecimal("1100"));
        vo.setClosePrice(null);
        when(blockTradeService.queryByCodeAndDateRange(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of(vo));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/block-trades", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tradeDate").value("20250702"))
                .andExpect(jsonPath("$.data.items[0].premiumRate").doesNotExist());
    }

    // ==================== 共享校验测试 ====================

    @Test
    void 非法股票代码返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/moneyflows", "INVALID"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(moneyflowService);
    }

    @Test
    void 非法日期范围返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/hk-holds", "000001.SZ")
                        .param("startDate", "20250201")
                        .param("endDate", "20250101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("startDate不能晚于endDate"));

        verifyNoInteractions(hkHoldService);
    }

    // ==================== 辅助 ====================

    private MockHttpServletRequestBuilder authenticatedGet(
            String urlTemplate, Object... uriVariables) {
        UserDO user = new UserDO();
        user.setUsername("tester");
        user.setEnabled(true);
        return get(urlTemplate, uriVariables).sessionAttr(SessionKeys.AUTH_USER, user);
    }
}
