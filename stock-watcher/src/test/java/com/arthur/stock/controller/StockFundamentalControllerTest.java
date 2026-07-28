package com.arthur.stock.controller;

import com.arthur.stock.constant.SessionKeys;
import com.arthur.stock.exception.GlobalExceptionHandler;
import com.arthur.stock.model.BalancesheetDO;
import com.arthur.stock.model.CashflowDO;
import com.arthur.stock.model.DailyBasicDO;
import com.arthur.stock.model.ExpressDO;
import com.arthur.stock.model.FinaIndicatorDO;
import com.arthur.stock.model.ForecastDO;
import com.arthur.stock.model.IncomeDO;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.dto.tushare.DividendDTO;
import com.arthur.stock.service.BalancesheetService;
import com.arthur.stock.service.CashflowService;
import com.arthur.stock.service.DailyBasicService;
import com.arthur.stock.service.DividendService;
import com.arthur.stock.service.ExpressService;
import com.arthur.stock.service.FinaIndicatorService;
import com.arthur.stock.service.ForecastService;
import com.arthur.stock.service.IncomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

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

@WebMvcTest(StockFundamentalController.class)
@Import({GlobalExceptionHandler.class, StockFundamentalControllerTest.TestConfig.class})
class StockFundamentalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DailyBasicService dailyBasicService;
    @Autowired
    private FinaIndicatorService finaIndicatorService;
    @Autowired
    private IncomeService incomeService;
    @Autowired
    private BalancesheetService balancesheetService;
    @Autowired
    private CashflowService cashflowService;
    @Autowired
    private ForecastService forecastService;
    @Autowired
    private ExpressService expressService;
    @Autowired
    private DividendService dividendService;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(dailyBasicService, finaIndicatorService, incomeService,
                balancesheetService, cashflowService, forecastService,
                expressService, dividendService);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean DailyBasicService dailyBasicService() { return Mockito.mock(DailyBasicService.class); }
        @Bean FinaIndicatorService finaIndicatorService() { return Mockito.mock(FinaIndicatorService.class); }
        @Bean IncomeService incomeService() { return Mockito.mock(IncomeService.class); }
        @Bean BalancesheetService balancesheetService() { return Mockito.mock(BalancesheetService.class); }
        @Bean CashflowService cashflowService() { return Mockito.mock(CashflowService.class); }
        @Bean ForecastService forecastService() { return Mockito.mock(ForecastService.class); }
        @Bean ExpressService expressService() { return Mockito.mock(ExpressService.class); }
        @Bean DividendService dividendService() { return Mockito.mock(DividendService.class); }
    }

    // ==================== daily-basics ====================

    @Test
    void dailyBasics_默认近五年并返回数据和元信息() throws Exception {
        LocalDate today = LocalDate.now();
        String startDate = today.minusYears(5).format(DateTimeFormatter.BASIC_ISO_DATE);
        String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        when(dailyBasicService.listByCodeAndDateRange("000001.SZ", startDate, endDate)).thenReturn(List.of(
                DailyBasicDO.builder().tsCode("000001.SZ").tradeDate("20250702").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/daily-basics", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tradeDate").value("20250702"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250702"))
                .andExpect(jsonPath("$.data.meta.startDate").value(startDate))
                .andExpect(jsonPath("$.data.meta.endDate").value(endDate))
                .andExpect(jsonPath("$.data.meta.limit").value(500));

        verify(dailyBasicService).listByCodeAndDateRange("000001.SZ", startDate, endDate);
    }

    @Test
    void dailyBasics_空数据返回稳定空列表() throws Exception {
        when(dailyBasicService.listByCodeAndDateRange(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/daily-basics", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== fina-indicators ====================

    @Test
    void finaIndicators_返回倒序数据和元信息() throws Exception {
        when(finaIndicatorService.queryLocalByTsCode("000001.SZ")).thenReturn(List.of(
                FinaIndicatorDO.builder().tsCode("000001.SZ").annDate("20250430").endDate("20250331").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/fina-indicators", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].annDate").value("20250430"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250430"))
                .andExpect(jsonPath("$.data.meta.limit").value(20));

        verify(finaIndicatorService).queryLocalByTsCode("000001.SZ");
    }

    @Test
    void finaIndicators_空数据返回稳定空列表() throws Exception {
        when(finaIndicatorService.queryLocalByTsCode("000001.SZ")).thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/fina-indicators", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== incomes ====================

    @Test
    void incomes_返回倒序数据和元信息() throws Exception {
        when(incomeService.queryLocalByTsCode("000001.SZ")).thenReturn(List.of(
                IncomeDO.builder().tsCode("000001.SZ").annDate("20250430").endDate("20250331").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/incomes", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].annDate").value("20250430"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250430"))
                .andExpect(jsonPath("$.data.meta.limit").value(20));
    }

    @Test
    void incomes_空数据返回稳定空列表() throws Exception {
        when(incomeService.queryLocalByTsCode("000001.SZ")).thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/incomes", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== balancesheets ====================

    @Test
    void balancesheets_返回倒序数据和元信息() throws Exception {
        when(balancesheetService.queryLocalByTsCode("000001.SZ")).thenReturn(List.of(
                BalancesheetDO.builder().tsCode("000001.SZ").annDate("20250430").endDate("20250331").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/balancesheets", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].annDate").value("20250430"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250430"))
                .andExpect(jsonPath("$.data.meta.limit").value(20));
    }

    @Test
    void balancesheets_空数据返回稳定空列表() throws Exception {
        when(balancesheetService.queryLocalByTsCode("000001.SZ")).thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/balancesheets", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== cashflows ====================

    @Test
    void cashflows_返回倒序数据和元信息() throws Exception {
        when(cashflowService.queryLocalByTsCode("000001.SZ")).thenReturn(List.of(
                CashflowDO.builder().tsCode("000001.SZ").annDate("20250430").endDate("20250331").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/cashflows", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].annDate").value("20250430"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250430"))
                .andExpect(jsonPath("$.data.meta.limit").value(20));
    }

    @Test
    void cashflows_空数据返回稳定空列表() throws Exception {
        when(cashflowService.queryLocalByTsCode("000001.SZ")).thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/cashflows", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== forecasts ====================

    @Test
    void forecasts_返回倒序数据和元信息() throws Exception {
        when(forecastService.queryLocalByTsCode("000001.SZ")).thenReturn(List.of(
                ForecastDO.builder().tsCode("000001.SZ").annDate("20250115").endDate("20241231").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/forecasts", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].annDate").value("20250115"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250115"))
                .andExpect(jsonPath("$.data.meta.limit").value(50));
    }

    @Test
    void forecasts_空数据返回稳定空列表() throws Exception {
        when(forecastService.queryLocalByTsCode("000001.SZ")).thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/forecasts", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== expresses ====================

    @Test
    void expresses_返回倒序数据和元信息() throws Exception {
        when(expressService.queryLocalByTsCode("000001.SZ")).thenReturn(List.of(
                ExpressDO.builder().tsCode("000001.SZ").annDate("20250115").endDate("20241231").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/expresses", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].annDate").value("20250115"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250115"))
                .andExpect(jsonPath("$.data.meta.limit").value(50));
    }

    @Test
    void expresses_空数据返回稳定空列表() throws Exception {
        when(expressService.queryLocalByTsCode("000001.SZ")).thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/expresses", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== dividends ====================

    @Test
    void dividends_返回倒序数据和元信息() throws Exception {
        when(dividendService.queryByTsCode("000001.SZ")).thenReturn(List.of(
                DividendDTO.builder().tsCode("000001.SZ").annDate("20250615").endDate("20241231").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/dividends", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].annDate").value("20250615"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250615"))
                .andExpect(jsonPath("$.data.meta.limit").value(50));
    }

    @Test
    void dividends_空数据返回稳定空列表() throws Exception {
        when(dividendService.queryByTsCode("000001.SZ")).thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/dividends", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== 共享校验测试 ====================

    @Test
    void 非法股票代码返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/daily-basics", "INVALID"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(dailyBasicService);
    }

    @Test
    void 非法日期范围返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/daily-basics", "000001.SZ")
                        .param("startDate", "20250201")
                        .param("endDate", "20250101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("startDate不能晚于endDate"));

        verifyNoInteractions(dailyBasicService);
    }

    @Test
    void limit越界返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/daily-basics", "000001.SZ")
                        .param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit必须在1到500之间"));

        verifyNoInteractions(dailyBasicService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedGet(
            String urlTemplate, Object... uriVariables) {
        UserDO user = new UserDO();
        user.setUsername("tester");
        user.setEnabled(true);
        return get(urlTemplate, uriVariables).sessionAttr(SessionKeys.AUTH_USER, user);
    }
}
