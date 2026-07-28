package com.arthur.stock.controller;

import com.arthur.stock.config.StrategyTemplateLoader;
import com.arthur.stock.constant.SessionKeys;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.exception.GlobalExceptionHandler;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.MarketService;
import com.arthur.stock.service.StockBasicService;
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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(PageController.class)
@Import({GlobalExceptionHandler.class, PageControllerTest.TestConfig.class})
class PageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StockBasicService stockBasicService;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(stockBasicService);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        MarketService marketService() {
            return Mockito.mock(MarketService.class);
        }

        @Bean
        StrategyTemplateLoader strategyTemplateLoader() {
            return Mockito.mock(StrategyTemplateLoader.class);
        }

        @Bean
        StockBasicService stockBasicService() {
            return Mockito.mock(StockBasicService.class);
        }
    }

    @Test
    void stockDetail_存在股票_应渲染四Tab且不设置侧边栏菜单() throws Exception {
        StockBasicDTO stock = StockBasicDTO.builder()
                .tsCode("000001.SZ")
                .name("平安银行")
                .build();
        when(stockBasicService.queryLocal("000001.SZ", null, null, null)).thenReturn(List.of(stock));

        mockMvc.perform(authenticatedGet("/page/stock-detail/{code}", "000001.SZ"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/stock-detail"))
                .andExpect(model().attribute("code", "000001.SZ"))
                .andExpect(model().attribute("stockName", "平安银行"))
                .andExpect(model().attribute("stockExists", true))
                .andExpect(model().attribute("pageTitle", "平安银行 · 个股诊断"))
                .andExpect(model().attributeDoesNotExist("activeMenu"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("技术面")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("基本面")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("资金面")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("风险提示")));
    }

    @Test
    void stockDetail_股票不存在_应显示未找到并停止渲染Tab() throws Exception {
        when(stockBasicService.queryLocal("999999.SH", null, null, null)).thenReturn(List.of());

        mockMvc.perform(authenticatedGet("/page/stock-detail/{code}", "999999.SH"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("stockExists", false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("未找到该股票")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-panel=\"technical\""))));
    }

    @Test
    void stockDetail_非法代码_应返回400且不查询股票() throws Exception {
        mockMvc.perform(authenticatedGet("/page/stock-detail/{code}", "INVALID"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(stockBasicService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedGet(
            String urlTemplate, Object... uriVariables) {
        UserDO user = new UserDO();
        user.setUsername("tester");
        user.setEnabled(true);
        return get(urlTemplate, uriVariables).sessionAttr(SessionKeys.AUTH_USER, user);
    }
}
