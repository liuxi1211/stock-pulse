package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.MarginDTO;
import com.arthur.stock.dto.tushare.MarginDetailDTO;
import com.arthur.stock.mapper.MarginDetailMapper;
import com.arthur.stock.mapper.MarginMapper;
import com.arthur.stock.model.MarginDO;
import com.arthur.stock.model.MarginDetailDO;
import com.arthur.stock.service.MarginService;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 融资融券数据服务实现。
 * <p>
 * 数据源：tushare margin / margin_detail 接口。
 * 落库策略：按业务键先删后插，保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginServiceImpl implements MarginService {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** sortBy 白名单 */
    private static final Set<String> SORT_WHITELIST = Set.of("rzrqye", "rzye", "rqye", "rzmre");
    /** order 白名单 */
    private static final Set<String> ORDER_WHITELIST = Set.of("asc", "desc");

    private final TushareClient tushareClient;
    private final MarginMapper marginMapper;
    private final MarginDetailMapper marginDetailMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveMargin(String tradeDate) {
        log.info("Fetching margin: tradeDate={}", tradeDate);

        List<MarginDTO> rows = tushareClient.margin(tradeDate, null);

        if (rows == null || rows.isEmpty()) {
            log.info("No margin data for tradeDate={}", tradeDate);
            return 0;
        }

        List<MarginDO> entities = rows.stream()
                .map(this::toMarginEntity)
                .filter(e -> e != null)
                .collect(Collectors.toList());

        int saved = persistMargin(entities);
        log.info("Saved {} margin records for tradeDate={}", saved, tradeDate);
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSaveMarginDetail(String tradeDate) {
        log.info("Fetching margin_detail: tradeDate={}", tradeDate);

        List<MarginDetailDTO> rows = tushareClient.marginDetail(tradeDate, null);

        if (rows == null || rows.isEmpty()) {
            log.info("No margin_detail data for tradeDate={}", tradeDate);
            return 0;
        }

        List<MarginDetailDO> entities = rows.stream()
                .map(this::toMarginDetailEntity)
                .filter(e -> e != null)
                .collect(Collectors.toList());

        int saved = persistMarginDetail(entities);
        log.info("Saved {} margin_detail records for tradeDate={}", saved, tradeDate);
        return saved;
    }

    @Override
    public List<MarginDO> queryTrend(int days, String exchangeId) {
        if (days <= 0) {
            return Collections.emptyList();
        }
        String endDate = LocalDate.now().format(DATE_FMT);
        String startDate = LocalDate.now().minusDays(days).format(DATE_FMT);

        // "ALL" 或 null 表示不按交易所过滤
        String effectiveExchangeId = (exchangeId == null || exchangeId.isBlank() || "ALL".equalsIgnoreCase(exchangeId))
                ? null : exchangeId;

        return marginMapper.selectTrend(startDate, endDate, effectiveExchangeId);
    }

    @Override
    public List<MarginDetailDO> queryDetailTop(String tradeDate, int limit, String sortBy, String order) {
        // tradeDate 为空时取最新
        String effectiveDate = tradeDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            effectiveDate = getLatestTradeDate();
            if (effectiveDate == null) {
                return Collections.emptyList();
            }
        }

        // sortBy 白名单校验，非法时默认 rzrqye
        String effectiveSortBy = (sortBy != null && SORT_WHITELIST.contains(sortBy.toLowerCase()))
                ? sortBy.toLowerCase() : "rzrqye";
        // order 白名单校验，非法时默认 desc
        String effectiveOrder = (order != null && ORDER_WHITELIST.contains(order.toLowerCase()))
                ? order.toLowerCase() : "desc";

        return marginDetailMapper.selectTopByTradeDate(effectiveDate, limit, effectiveSortBy, effectiveOrder);
    }

    @Override
    public String getLatestTradeDate() {
        // 优先从 margin_detail 取，回退到 margin
        String date = marginDetailMapper.selectLatestTradeDate();
        if (date != null) {
            return date;
        }
        return marginMapper.selectLatestTradeDate();
    }

    // ==================== 内部方法 ====================

    /**
     * 按业务键 (exchange_id, trade_date) 批量先删后插，保证幂等。
     */
    private int persistMargin(List<MarginDO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<MarginDO> batch : Lists.partition(rows, BATCH_SIZE)) {
            marginMapper.deleteBatchByKeys(batch);
            count += marginMapper.insertBatch(batch);
        }
        return count;
    }

    /**
     * 按业务键 (trade_date, ts_code) 批量先删后插，保证幂等。
     */
    private int persistMarginDetail(List<MarginDetailDO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<MarginDetailDO> batch : Lists.partition(rows, BATCH_SIZE)) {
            marginDetailMapper.deleteBatchByKeys(batch);
            count += marginDetailMapper.insertBatch(batch);
        }
        return count;
    }

    private MarginDO toMarginEntity(MarginDTO dto) {
        if (dto == null || dto.getExchangeId() == null || dto.getTradeDate() == null) {
            return null;
        }
        return MarginDO.builder()
                .exchangeId(dto.getExchangeId())
                .tradeDate(dto.getTradeDate())
                .rzye(dto.getRzye())
                .rzmre(dto.getRzmre())
                .rzche(dto.getRzche())
                .rqye(dto.getRqye())
                .rqmcl(dto.getRqmcl())
                .rzrqye(dto.getRzrqye())
                .build();
    }

    private MarginDetailDO toMarginDetailEntity(MarginDetailDTO dto) {
        if (dto == null || dto.getTradeDate() == null || dto.getTsCode() == null) {
            return null;
        }
        return MarginDetailDO.builder()
                .tradeDate(dto.getTradeDate())
                .tsCode(dto.getTsCode())
                .name(dto.getName())
                .rzye(dto.getRzye())
                .rqye(dto.getRqye())
                .rzmre(dto.getRzmre())
                .rzche(dto.getRzche())
                .rqmcl(dto.getRqmcl())
                .rzrqye(dto.getRzrqye())
                .build();
    }
}
