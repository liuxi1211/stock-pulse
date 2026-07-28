package com.arthur.stock.controller;

import com.arthur.stock.constant.SessionKeys;
import com.arthur.stock.dto.tushare.StkHoldernumberDTO;
import com.arthur.stock.exception.GlobalExceptionHandler;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.StkHoldernumberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StkHoldernumberController.class)
@Import({GlobalExceptionHandler.class, StkHoldernumberControllerTest.TestConfig.class})
class StkHoldernumberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StkHoldernumberService stkHoldernumberService;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(stkHoldernumberService);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        StkHoldernumberService stkHoldernumberService() {
            return Mockito.mock(StkHoldernumberService.class);
        }
    }

    @Test
    void query_默认返回最近20期() throws Exception {
        when(stkHoldernumberService.queryRecent("000001.SZ", 20)).thenReturn(List.of(
                StkHoldernumberDTO.builder()
                        .tsCode("000001.SZ")
                        .annDate("20250430")
                        .endDate("20250331")
                        .holderNum(100000L)
                        .build()));

        mockMvc.perform(authenticatedGet("/api/stk-holdernumber").param("tsCode", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tsCode").value("000001.SZ"))
                .andExpect(jsonPath("$.data[0].endDate").value("20250331"))
                .andExpect(jsonPath("$.data[0].holderNum").value(100000));

        verify(stkHoldernumberService).queryRecent("000001.SZ", 20);
    }

    @Test
    void query_limit透传() throws Exception {
        when(stkHoldernumberService.queryRecent("600000.SH", 8)).thenReturn(List.of());

        mockMvc.perform(authenticatedGet("/api/stk-holdernumber")
                        .param("tsCode", "600000.SH")
                        .param("limit", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(stkHoldernumberService).queryRecent("600000.SH", 8);
    }

    @Test
    void query_非法股票代码返回400() throws Exception {
        mockMvc.perform(authenticatedGet("/api/stk-holdernumber").param("tsCode", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("股票代码格式必须为6位数字.SH、.SZ或.BJ"));

        verifyNoInteractions(stkHoldernumberService);
    }

    @Test
    void query_limit越界返回400() throws Exception {
        mockMvc.perform(authenticatedGet("/api/stk-holdernumber")
                        .param("tsCode", "000001.SZ")
                        .param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit必须在1到500之间"));

        verifyNoInteractions(stkHoldernumberService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedGet(String url) {
        UserDO user = new UserDO();
        user.setUsername("tester");
        user.setEnabled(true);
        return get(url).sessionAttr(SessionKeys.AUTH_USER, user);
    }
}
