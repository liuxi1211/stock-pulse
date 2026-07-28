package com.arthur.stock.controller;

import com.arthur.stock.constant.SessionKeys;
import com.arthur.stock.exception.GlobalExceptionHandler;
import com.arthur.stock.model.StockNamechangeDO;
import com.arthur.stock.model.StockStkLimitDO;
import com.arthur.stock.model.StockSuspendDDO;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.StockNamechangeService;
import com.arthur.stock.service.StockStkLimitService;
import com.arthur.stock.service.StockSuspendDService;
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
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockRiskController.class)
@Import({GlobalExceptionHandler.class, StockRiskControllerTest.TestConfig.class})
class StockRiskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StockStkLimitService stockStkLimitService;
    @Autowired
    private StockSuspendDService stockSuspendDService;
    @Autowired
    private StockNamechangeService stockNamechangeService;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(stockStkLimitService, stockSuspendDService, stockNamechangeService);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean StockStkLimitService stockStkLimitService() { return Mockito.mock(StockStkLimitService.class); }
        @Bean StockSuspendDService stockSuspendDService() { return Mockito.mock(StockSuspendDService.class); }
        @Bean StockNamechangeService stockNamechangeService() { return Mockito.mock(StockNamechangeService.class); }
    }

    // ==================== limits ====================

    @Test
    void limits_默认近30天并返回倒序数据和元信息() throws Exception {
        LocalDate today = LocalDate.now();
        String startDate = today.minusDays(30).format(DateTimeFormatter.BASIC_ISO_DATE);
        String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        when(stockStkLimitService.listByRange(List.of("000001.SZ"), startDate, endDate))
                .thenReturn(Map.of("000001.SZ", Map.of(
                        "20250702", StockStkLimitDO.builder()
                                .tsCode("000001.SZ").tradeDate("20250702")
                                .upLimit(11.0).downLimit(9.0).build())));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/limits", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tradeDate").value("20250702"))
                .andExpect(jsonPath("$.data.items[0].upLimit").value(11.0))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250702"))
                .andExpect(jsonPath("$.data.meta.startDate").value(startDate))
                .andExpect(jsonPath("$.data.meta.endDate").value(endDate))
                .andExpect(jsonPath("$.data.meta.limit").value(30));

        verify(stockStkLimitService).listByRange(List.of("000001.SZ"), startDate, endDate);
    }

    @Test
    void limits_空数据返回稳定空列表() throws Exception {
        when(stockStkLimitService.listByRange(Mockito.anyList(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Collections.emptyMap());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/limits", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== suspends ====================

    @Test
    void suspends_默认近一年并返回倒序数据和元信息() throws Exception {
        LocalDate today = LocalDate.now();
        String startDate = today.minusYears(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        when(stockSuspendDService.queryEventsByTsCode("000001.SZ", startDate, endDate))
                .thenReturn(List.of(
                        StockSuspendDDO.builder()
                                .tsCode("000001.SZ").tradeDate("20250702")
                                .suspendType("S").build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/suspends", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tradeDate").value("20250702"))
                .andExpect(jsonPath("$.data.items[0].suspendType").value("S"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250702"))
                .andExpect(jsonPath("$.data.meta.startDate").value(startDate))
                .andExpect(jsonPath("$.data.meta.endDate").value(endDate))
                .andExpect(jsonPath("$.data.meta.limit").value(100));

        verify(stockSuspendDService).queryEventsByTsCode("000001.SZ", startDate, endDate);
    }

    @Test
    void suspends_空数据返回稳定空列表() throws Exception {
        when(stockSuspendDService.queryEventsByTsCode(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/suspends", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== namechanges ====================

    @Test
    void namechanges_返回倒序数据和元信息() throws Exception {
        when(stockNamechangeService.listByTsCodes(List.of("000001.SZ")))
                .thenReturn(Map.of("000001.SZ", List.of(
                        StockNamechangeDO.builder()
                                .tsCode("000001.SZ").name("平安银行")
                                .startDate("20100101").build())));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/namechanges", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("平安银行"))
                .andExpect(jsonPath("$.data.items[0].startDate").value("20100101"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20100101"))
                .andExpect(jsonPath("$.data.meta.limit").value(50));

        verify(stockNamechangeService).listByTsCodes(List.of("000001.SZ"));
    }

    @Test
    void namechanges_空数据返回稳定空列表() throws Exception {
        when(stockNamechangeService.listByTsCodes(Mockito.anyList()))
                .thenReturn(Collections.emptyMap());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/namechanges", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    // ==================== 共享校验测试 ====================

    @Test
    void 非法股票代码返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/limits", "INVALID"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(stockStkLimitService);
    }

    @Test
    void 非法日期范围返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/limits", "000001.SZ")
                        .param("startDate", "20250201")
                        .param("endDate", "20250101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("startDate不能晚于endDate"));

        verifyNoInteractions(stockStkLimitService);
    }

    @Test
    void limit越界返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/limits", "000001.SZ")
                        .param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit必须在1到500之间"));

        verifyNoInteractions(stockStkLimitService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedGet(
            String urlTemplate, Object... uriVariables) {
        UserDO user = new UserDO();
        user.setUsername("tester");
        user.setEnabled(true);
        return get(urlTemplate, uriVariables).sessionAttr(SessionKeys.AUTH_USER, user);
    }
}
