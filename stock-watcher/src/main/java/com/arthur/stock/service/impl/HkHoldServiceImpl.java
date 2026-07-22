package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.HkHoldTrendVO;
import com.arthur.stock.dto.tushare.HkHoldDTO;
import com.arthur.stock.mapper.HkHoldMapper;
import com.arthur.stock.model.HkHoldDO;
import com.arthur.stock.service.HkHoldService;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 沪深港通持股明细服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HkHoldServiceImpl implements HkHoldService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final HkHoldMapper hkHoldMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSave(String tradeDate) {
        log.info("Fetching hk_hold for trade_date={}", tradeDate);

        List<HkHoldDTO> dtos = tushareClient.hkHold(tradeDate, null);
        if (dtos.isEmpty()) {
            log.info("No hk_hold data returned for trade_date={}", tradeDate);
            return 0;
        }

        List<HkHoldDO> entities = dtos.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveBatch(entities);
        log.info("Saved {} hk_hold records for trade_date={}", entities.size(), tradeDate);
        return entities.size();
    }

    @Override
    public List<HkHoldTrendVO> queryRatioTrend(int days, String exchangeId) {
        String endDate = LocalDate.now().format(DATE_FMT);
        String startDate = LocalDate.now().minusDays(days).format(DATE_FMT);
        String effectiveExchangeId = normalizeExchangeId(exchangeId);
        return hkHoldMapper.selectRatioTrend(startDate, endDate, effectiveExchangeId);
    }

    @Override
    public List<HkHoldDO> queryTopHoldings(String tradeDate, String exchangeId, int limit) {
        String effectiveDate = tradeDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            effectiveDate = hkHoldMapper.selectLatestTradeDate();
            if (effectiveDate == null) {
                return Collections.emptyList();
            }
        }
        String effectiveExchangeId = normalizeExchangeId(exchangeId);
        return hkHoldMapper.selectTopHoldings(effectiveDate, effectiveExchangeId, limit);
    }

    @Override
    public List<HkHoldDO> queryDetail(String tsCode, int days) {
        if (tsCode == null || tsCode.isBlank()) {
            return Collections.emptyList();
        }
        String endDate = LocalDate.now().format(DATE_FMT);
        String startDate = LocalDate.now().minusDays(days).format(DATE_FMT);
        return hkHoldMapper.selectByCodeAndDateRange(tsCode, startDate, endDate);
    }

    @Override
    public String getLatestTradeDate() {
        return hkHoldMapper.selectLatestTradeDate();
    }

    /**
     * 规范化 exchangeId：null/空/"ALL" 统一转为 null（表示不过滤）。
     */
    private String normalizeExchangeId(String exchangeId) {
        if (exchangeId == null || exchangeId.isBlank() || "ALL".equalsIgnoreCase(exchangeId)) {
            return null;
        }
        return exchangeId;
    }

    private HkHoldDO toEntity(HkHoldDTO dto) {
        if (dto == null || dto.getTradeDate() == null || dto.getCode() == null) {
            return null;
        }
        return HkHoldDO.builder()
                .tradeDate(dto.getTradeDate())
                .code(dto.getCode())
                .name(dto.getName())
                .vol(dto.getVol())
                .ratio(dto.getRatio())
                .tsCode(dto.getTsCode())
                .exchangeId(dto.getExchangeId())
                .build();
    }

    /**
     * 批量保存：先删除同主键（trade_date + code）已存在记录，再插入；跨方言通用。
     */
    private void saveBatch(List<HkHoldDO> list) {
        Lists.partition(list, BATCH_SIZE).forEach(batch -> {
            hkHoldMapper.deleteBatchByKeys(batch);
            hkHoldMapper.insertBatch(batch);
        });
    }
}
