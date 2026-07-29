package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.service.IndexDailyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IndexDailyController 单元测试（板块行情 K 线接口修复验证）。
 * <p>
 * 重点验证：
 * <ul>
 *   <li>{@code getLatest}：codes 为空/空白返回空列表，正常逗号分隔解析；</li>
 *   <li>{@code query}：service 返回 DESC，controller 翻转为 ASC（首元素日期 &lt; 末元素日期）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IndexDailyControllerTest {

    @Mock
    private IndexDailyService indexDailyService;

    @InjectMocks
    private IndexDailyController controller;

    // ==================== getLatest ====================

    @Test
    void getLatest_codes为空返回空列表() {
        ApiResponse<List<IndexDailyDO>> resp = controller.getLatest("");
        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).isEmpty();
        verify(indexDailyService, never()).getLatestByCodes(any());
    }

    @Test
    void getLatest_codes为空白返回空列表() {
        ApiResponse<List<IndexDailyDO>> resp = controller.getLatest("  ,  ,  ");
        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).isEmpty();
        verify(indexDailyService, never()).getLatestByCodes(any());
    }

    @Test
    void getLatest_逗号分隔解析并透传service() {
        when(indexDailyService.getLatestByCodes(Arrays.asList("000001.SH", "399001.SZ")))
                .thenReturn(List.of(IndexDailyDO.builder().tsCode("000001.SH").tradeDate("20260728").build()));

        ApiResponse<List<IndexDailyDO>> resp = controller.getLatest("000001.SH, 399001.SZ ,");

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).hasSize(1);
        verify(indexDailyService).getLatestByCodes(Arrays.asList("000001.SH", "399001.SZ"));
    }

    // ==================== query ====================

    @Test
    void query_service返回DESC_controller翻转为ASC() {
        List<IndexDailyDO> descByDate = Arrays.asList(
                IndexDailyDO.builder().tsCode("801010.SI").tradeDate("20260728").close(new BigDecimal("1000")).build(),
                IndexDailyDO.builder().tsCode("801010.SI").tradeDate("20260725").close(new BigDecimal("995")).build(),
                IndexDailyDO.builder().tsCode("801010.SI").tradeDate("20260724").close(new BigDecimal("990")).build());
        when(indexDailyService.getByCodeOrderByTradeDate("801010.SI", 250)).thenReturn(descByDate);

        ApiResponse<List<IndexDailyDO>> resp = controller.query("801010.SI", null, null, 250);

        assertThat(resp.getCode()).isEqualTo(200);
        List<IndexDailyDO> data = resp.getData();
        assertThat(data).hasSize(3);
        assertThat(data.get(0).getTradeDate()).isEqualTo("20260724");
        assertThat(data.get(2).getTradeDate()).isEqualTo("20260728");
        verify(indexDailyService).getByCodeOrderByTradeDate("801010.SI", 250);
    }

    @Test
    void query_单条数据翻转仍正确() {
        when(indexDailyService.getByCodeOrderByTradeDate(eq("801010.SI"), anyInt()))
                .thenReturn(List.of(IndexDailyDO.builder().tsCode("801010.SI").tradeDate("20260728").build()));

        ApiResponse<List<IndexDailyDO>> resp = controller.query("801010.SI", null, null, 60);

        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getTradeDate()).isEqualTo("20260728");
    }
}
