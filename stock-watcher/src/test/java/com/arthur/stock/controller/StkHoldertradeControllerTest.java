package com.arthur.stock.controller;

import com.arthur.stock.constant.SessionKeys;
import com.arthur.stock.dto.tushare.StkHoldertradeDTO;
import com.arthur.stock.exception.GlobalExceptionHandler;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.StkHoldertradeService;
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
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StkHoldertradeController.class)
@Import({GlobalExceptionHandler.class, StkHoldertradeControllerTest.TestConfig.class})
class StkHoldertradeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StkHoldertradeService service;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(service);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        StkHoldertradeService stkHoldertradeService() {
            return Mockito.mock(StkHoldertradeService.class);
        }
    }

    @Test
    void query_默认近一年并返回倒序数据和元信息() throws Exception {
        LocalDate today = LocalDate.now();
        String startDate = today.minusYears(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        when(service.queryByDateRange("000001.SZ", startDate, endDate)).thenReturn(List.of(
                StkHoldertradeDTO.builder()
                        .tsCode("000001.SZ")
                        .annDate("20250702")
                        .holderName("股东甲")
                        .inDe("DE")
                        .build()));

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/holder-trades", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].annDate").value("20250702"))
                .andExpect(jsonPath("$.data.meta.symbol").value("000001.SZ"))
                .andExpect(jsonPath("$.data.meta.dataAsOf").value("20250702"))
                .andExpect(jsonPath("$.data.meta.startDate").value(startDate))
                .andExpect(jsonPath("$.data.meta.endDate").value(endDate));

        verify(service).queryByDateRange("000001.SZ", startDate, endDate);
    }

    @Test
    void query_日期范围透传() throws Exception {
        when(service.queryByDateRange("600000.SH", "20250101", "20250630")).thenReturn(List.of());

        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/holder-trades", "600000.SH")
                        .param("startDate", "20250101")
                        .param("endDate", "20250630"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.meta.dataAsOf").doesNotExist());
    }

    @Test
    void query_非法股票代码返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/holder-trades", "INVALID"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void query_非法日期范围返回400且不查询() throws Exception {
        mockMvc.perform(authenticatedGet("/stocks/{tsCode}/holder-trades", "000001.SZ")
                        .param("startDate", "20250201")
                        .param("endDate", "20250101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("startDate不能晚于endDate"));

        verifyNoInteractions(service);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedGet(
            String urlTemplate, Object... uriVariables) {
        UserDO user = new UserDO();
        user.setUsername("tester");
        user.setEnabled(true);
        return get(urlTemplate, uriVariables).sessionAttr(SessionKeys.AUTH_USER, user);
    }
}
