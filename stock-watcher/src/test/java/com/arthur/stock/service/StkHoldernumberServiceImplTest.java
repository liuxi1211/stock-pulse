package com.arthur.stock.service;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.TushareApiEnum;
import com.arthur.stock.dto.tushare.StkHoldernumberDTO;
import com.arthur.stock.mapper.StkHoldernumberMapper;
import com.arthur.stock.model.StkHoldernumberDO;
import com.arthur.stock.service.impl.StkHoldernumberServiceImpl;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StkHoldernumberServiceImplTest {

    private TushareClient tushareClient;
    private StkHoldernumberMapper stkHoldernumberMapper;
    private StkHoldernumberServiceImpl service;

    @BeforeEach
    void setUp() {
        tushareClient = mock(TushareClient.class);
        stkHoldernumberMapper = mock(StkHoldernumberMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = new StkHoldernumberServiceImpl(
                tushareClient,
                stkHoldernumberMapper,
                mock(StockBasicService.class),
                transactionTemplate);
    }

    @Test
    void fetchAndSave_映射并幂等写入() {
        StkHoldernumberDTO dto = StkHoldernumberDTO.builder()
                .tsCode("000001.SZ")
                .annDate("20250430")
                .endDate("20250331")
                .holderNum(123456L)
                .build();
        mockQueryWithPaging(List.of(dto));
        when(stkHoldernumberMapper.insertBatch(any())).thenReturn(1);

        int saved = service.fetchAndSave("000001.SZ");

        assertEquals(1, saved);
        ArgumentCaptor<List<StkHoldernumberDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(stkHoldernumberMapper).deleteBatchByKeys(captor.capture());
        verify(stkHoldernumberMapper).insertBatch(any());
        StkHoldernumberDO entity = captor.getValue().getFirst();
        assertEquals("000001.SZ", entity.getTsCode());
        assertEquals("20250430", entity.getAnnDate());
        assertEquals("20250331", entity.getEndDate());
        assertEquals(123456L, entity.getHolderNum());
    }

    @Test
    void fetchAndSave_接口失败不触碰历史数据() {
        when(tushareClient.queryWithPaging(
                eq(TushareApiEnum.STK_HOLDERNUMBER), any(JSONObject.class),
                eq(StkHoldernumberDTO.class), anyInt(), any()))
                .thenThrow(new RuntimeException("remote failed"));

        assertThrows(RuntimeException.class, () -> service.fetchAndSave("000001.SZ"));

        verify(stkHoldernumberMapper, never()).deleteBatchByKeys(any());
        verify(stkHoldernumberMapper, never()).insertBatch(any());
    }

    @Test
    void queryRecent_保持Mapper日期排序结果() {
        when(stkHoldernumberMapper.selectRecentByTsCode("000001.SZ", 2)).thenReturn(List.of(
                entity("20250331", 100L),
                entity("20241231", 120L)));

        List<StkHoldernumberDTO> result = service.queryRecent("000001.SZ", 2);

        assertEquals(List.of("20250331", "20241231"), result.stream().map(StkHoldernumberDTO::getEndDate).toList());
        verify(stkHoldernumberMapper).selectRecentByTsCode("000001.SZ", 2);
    }

    /**
     * 模拟 queryWithPaging 回调：调用 handler 一次，传入指定数据，返回总数。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockQueryWithPaging(List<StkHoldernumberDTO> rows) {
        when(tushareClient.queryWithPaging(
                any(TushareApiEnum.class), any(JSONObject.class),
                eq(StkHoldernumberDTO.class), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Consumer<List<StkHoldernumberDTO>> handler = invocation.getArgument(4);
                    handler.accept(rows);
                    return rows.size();
                });
    }

    private StkHoldernumberDO entity(String endDate, long holderNum) {
        return StkHoldernumberDO.builder()
                .tsCode("000001.SZ")
                .annDate(endDate)
                .endDate(endDate)
                .holderNum(holderNum)
                .build();
    }
}
