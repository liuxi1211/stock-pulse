package com.arthur.stock.controller;

import com.arthur.stock.constant.SessionKeys;
import com.arthur.stock.exception.GlobalExceptionHandler;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.KlineService;
import com.arthur.stock.vo.KlineDataVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockDiagnosisController.class)
@Import({GlobalExceptionHandler.class, StockDiagnosisControllerTest.TestConfig.class})
class StockDiagnosisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KlineService klineService;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(klineService);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        KlineService klineService() {
            return Mockito.mock(KlineService.class);
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedGet(
            String urlTemplate, Object... uriVariables) {
        UserDO user = new UserDO();
        user.setUsername("tester");
        user.setEnabled(true);
        return get(urlTemplate, uriVariables).sessionAttr(SessionKeys.AUTH_USER, user);
    }

    @Test
    void getKline_合法请求_应返回数据和元信息() throws Exception {
        List<KlineDataVO> data = List.of(
                bar("20240102", "10.00"),
                bar("20240103", "10.20"));
        when(klineService.getKlineData("000001.SZ", "daily", "QFQ", "00000000", "99999999")).thenReturn(data);

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20240103"))
                .andExpect(jsonPath("$.data.meta.period").value("D"))
                .andExpect(jsonPath("$.data.meta.adjustment").value("QFQ"))
                .andExpect(jsonPath("$.data.meta.limit").value(250));

        verify(klineService).getKlineData("000001.SZ", "daily", "QFQ", "00000000", "99999999");
    }

    @Test
    void getKline_合法空数据_应返回稳定空列表和元信息() throws Exception {
        when(klineService.getKlineData("430047.BJ", "weekly", "QFQ", "00000000", "99999999"))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "430047.BJ")
                        .param("period", "W")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.symbol").value("430047.BJ"))
                .andExpect(jsonPath("$.data.meta.period").value("W"))
                .andExpect(jsonPath("$.data.meta.adjustment").value("QFQ"))
                .andExpect(jsonPath("$.data.meta.limit").value(20))
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    @Test
    void getKline_日期范围_应传递规范化边界() throws Exception {
        when(klineService.getKlineData("600000.SH", "monthly", "QFQ", "20240101", "20241231"))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "600000.SH")
                        .param("period", "M")
                        .param("startDate", "20240101")
                        .param("endDate", "20241231"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meta.startDate").value("20240101"))
                .andExpect(jsonPath("$.data.meta.endDate").value("20241231"));

        verify(klineService).getKlineData("600000.SH", "monthly", "QFQ", "20240101", "20241231");
    }

    @Test
    void getKline_非法股票代码_应返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("股票代码格式必须为6位数字.SH、.SZ或.BJ"));

        verifyNoInteractions(klineService);
    }

    @Test
    void getKline_不支持交易所_应返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "000001.HK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(klineService);
    }

    @Test
    void getKline_日期格式非法_应返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "000001.SZ")
                        .param("startDate", "2024-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("startDate格式必须为yyyyMMdd"));

        verifyNoInteractions(klineService);
    }

    @Test
    void getKline_开始日期晚于结束日期_应返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "000001.SZ")
                        .param("startDate", "20240201")
                        .param("endDate", "20240101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("startDate不能晚于endDate"));

        verifyNoInteractions(klineService);
    }

    @Test
    void getKline_limit越界_应返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "000001.SZ")
                        .param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("limit必须在1到500之间"));

        verifyNoInteractions(klineService);
    }

    @Test
    void getKline_60分周期未接入_应返回400且提示不支持() throws Exception {
        when(klineService.getKlineData("000001.SZ", "60MIN", "QFQ", "00000000", "99999999"))
                .thenThrow(new IllegalArgumentException("当前数据源不支持60MIN周期"));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "000001.SZ")
                        .param("period", "60MIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("当前数据源不支持60MIN周期"));

        verify(klineService).getKlineData("000001.SZ", "60MIN", "QFQ", "00000000", "99999999");
    }

    @Test
    void getKline_HFQ复权_应传递并返回元信息() throws Exception {
        when(klineService.getKlineData("000001.SZ", "daily", "HFQ", "00000000", "99999999"))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/kline", "000001.SZ")
                        .param("adj", "HFQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meta.adjustment").value("HFQ"));

        verify(klineService).getKlineData("000001.SZ", "daily", "HFQ", "00000000", "99999999");
    }

    private static KlineDataVO bar(String date, String close) {
        BigDecimal price = new BigDecimal(close);
        return KlineDataVO.builder()
                .date(date)
                .open(price)
                .high(price)
                .low(price)
                .close(price)
                .volume(100L)
                .build();
    }
}


