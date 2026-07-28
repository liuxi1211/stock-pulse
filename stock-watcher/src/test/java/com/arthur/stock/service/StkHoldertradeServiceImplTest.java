package com.arthur.stock.service;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.TushareApiEnum;
import com.arthur.stock.dto.tushare.StkHoldertradeDTO;
import com.arthur.stock.mapper.StkHoldertradeMapper;
import com.arthur.stock.model.StkHoldertradeDO;
import com.arthur.stock.service.impl.StkHoldertradeServiceImpl;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StkHoldertradeServiceImplTest {

    private TushareClient tushareClient;
    private StkHoldertradeMapper mapper;
    private StkHoldertradeServiceImpl service;

    @BeforeEach
    void setUp() {
        tushareClient = mock(TushareClient.class);
        mapper = mock(StkHoldertradeMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        // transactionTemplate.execute(callback) -> 执行 callback 并返回其结果
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = new StkHoldertradeServiceImpl(
                tushareClient, mapper, mock(StockBasicService.class), transactionTemplate);
    }

    @Test
    void fetchAndSave_映射去重并幂等写入() {
        StkHoldertradeDTO row = trade("20250701", "20250630");
        // 模拟 queryWithPaging：回调一次传入两条重复数据（测试去重）
        mockQueryWithPaging(List.of(row, row));
        // mock insertBatch 返回写入条数
        when(mapper.insertBatch(any())).thenReturn(1);

        int saved = service.fetchAndSave("000001.SZ", "20250101", "20251231");

        assertEquals(1, saved);
        ArgumentCaptor<List<StkHoldertradeDO>> entityCaptor = ArgumentCaptor.forClass(List.class);
        verify(mapper).deleteBatchByKeys(entityCaptor.capture());
        verify(mapper).insertBatch(any());
        StkHoldertradeDO entity = entityCaptor.getValue().getFirst();
        assertEquals("股东甲", entity.getHolderName());
        assertEquals(new BigDecimal("120.50"), entity.getChangeVol());
    }

    @Test
    void fetchAndSave_接口失败不触碰历史数据() {
        // 模拟 queryWithPaging 抛异常
        when(tushareClient.queryWithPaging(
                eq(TushareApiEnum.STK_HOLDERTRADE), any(JSONObject.class),
                eq(StkHoldertradeDTO.class), anyInt(), any()))
                .thenThrow(new RuntimeException("remote failed"));

        assertThrows(RuntimeException.class,
                () -> service.fetchAndSave("000001.SZ", "20250101", "20251231"));

        verify(mapper, never()).deleteBatchByKeys(any());
        verify(mapper, never()).insertBatch(any());
    }

    @Test
    void queryByDateRange_保持Mapper倒序结果() {
        when(mapper.selectByTsCodeAndDateRange("000001.SZ", "20250101", "20251231"))
                .thenReturn(List.of(entity("20250702"), entity("20250701")));

        List<StkHoldertradeDTO> result = service.queryByDateRange("000001.SZ", "20250101", "20251231");

        assertEquals(List.of("20250702", "20250701"), result.stream().map(StkHoldertradeDTO::getAnnDate).toList());
    }

    /**
     * 模拟 queryWithPaging 回调：调用 handler 一次，传入指定数据，返回总数。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockQueryWithPaging(List<StkHoldertradeDTO> rows) {
        when(tushareClient.queryWithPaging(
                any(TushareApiEnum.class), any(JSONObject.class),
                eq(StkHoldertradeDTO.class), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Consumer<List<StkHoldertradeDTO>> handler = invocation.getArgument(4);
                    handler.accept(rows);
                    return rows.size();
                });
    }

    private StkHoldertradeDTO trade(String annDate, String closeDate) {
        return StkHoldertradeDTO.builder()
                .tsCode("000001.SZ")
                .annDate(annDate)
                .holderName("股东甲")
                .holderType("G")
                .inDe("DE")
                .changeVol(new BigDecimal("120.50"))
                .beginDate("20250601")
                .closeDate(closeDate)
                .build();
    }

    private StkHoldertradeDO entity(String annDate) {
        return StkHoldertradeDO.builder()
                .tsCode("000001.SZ")
                .annDate(annDate)
                .holderName("股东甲")
                .inDe("DE")
                .beginDate("20250601")
                .closeDate("20250630")
                .build();
    }
}
