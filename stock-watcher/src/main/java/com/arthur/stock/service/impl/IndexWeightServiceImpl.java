package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.IndexWeightDTO;
import com.arthur.stock.dto.tushare.IndexWeightQueryDTO;
import com.arthur.stock.mapper.IndexWeightMapper;
import com.arthur.stock.model.IndexWeightDO;
import com.arthur.stock.service.IndexWeightService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 指数成分股权重服务实现。
 * <p>
 * 数据源：tushare index_weight 接口（需 2000 积分，当前 token 满足）。
 * 落库策略：按 (ts_code, trade_date) 维度先删后插，实现幂等 upsert。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexWeightServiceImpl implements IndexWeightService {

    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final IndexWeightMapper indexWeightMapper;

    @Override
    public int fetchAndSave(String indexCode, String tradeDate) {
        log.info("Fetching index_weight: indexCode={}, tradeDate={}", indexCode, tradeDate);

        IndexWeightQueryDTO param = IndexWeightQueryDTO.builder()
                .indexCode(indexCode)
                .tradeDate(tradeDate)
                .build();
        List<IndexWeightDTO> rows = tushareClient.indexWeight(param);

        if (rows.isEmpty()) {
            log.info("No index_weight data for indexCode={}, tradeDate={}", indexCode, tradeDate);
            return 0;
        }

        List<IndexWeightDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        int saved = saveBatch(indexCode, tradeDate, entities);
        log.info("Saved {} index_weight records for indexCode={}, tradeDate={}",
                saved, indexCode, tradeDate);
        return saved;
    }

    @Override
    public int fetchAndSaveRange(String indexCode, String startDate, String endDate) {
        log.info("Fetching index_weight range: indexCode={}, {}~{}", indexCode, startDate, endDate);

        IndexWeightQueryDTO param = IndexWeightQueryDTO.builder()
                .indexCode(indexCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<IndexWeightDTO> rows = tushareClient.indexWeight(param);

        if (rows.isEmpty()) {
            log.info("No index_weight data for indexCode={}, {}~{}", indexCode, startDate, endDate);
            return 0;
        }

        List<IndexWeightDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        int total = 0;
        List<String> dates = entities.stream()
                .map(IndexWeightDO::getTradeDate)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        for (String date : dates) {
            List<IndexWeightDO> dayRows = entities.stream()
                    .filter(e -> date.equals(e.getTradeDate()))
                    .collect(Collectors.toList());
            total += saveBatch(indexCode, date, dayRows);
        }
        log.info("Saved {} index_weight records for indexCode={}, {}~{} ({} trade dates)",
                total, indexCode, startDate, endDate, dates.size());
        return total;
    }

    @Override
    public List<String> getLatestConstituents(String indexCode) {
        List<String> codes = indexWeightMapper.selectLatestConstituents(indexCode);
        return codes == null ? Collections.emptyList() : codes;
    }

    @Override
    public List<String> getConstituentsAt(String indexCode, String tradeDate) {
        List<String> codes = indexWeightMapper.selectConstituentsAt(indexCode, tradeDate);
        return codes == null ? Collections.emptyList() : codes;
    }

    @Override
    public String getEffectiveDate(String indexCode, String tradeDate) {
        return indexWeightMapper.selectEffectiveDate(indexCode, tradeDate);
    }

    @Override
    public List<String> getConstituentsInRange(String indexCode, String startDate, String endDate) {
        List<String> codes = indexWeightMapper.selectConstituentsInRange(indexCode, startDate, endDate);
        return codes == null ? Collections.emptyList() : codes;
    }

    private IndexWeightDO toEntity(IndexWeightDTO dto) {
        if (dto == null || dto.getTsCode() == null || dto.getTradeDate() == null || dto.getConCode() == null) {
            return null;
        }
        return IndexWeightDO.builder()
                .tsCode(dto.getTsCode())
                .tradeDate(dto.getTradeDate())
                .conCode(dto.getConCode())
                .weight(dto.getWeight())
                .build();
    }

    /**
     * 按 (ts_code, trade_date) 先删后插，实现幂等。
     */
    private int saveBatch(String indexCode, String tradeDate, List<IndexWeightDO> rows) {
        indexWeightMapper.delete(new LambdaQueryWrapper<IndexWeightDO>()
                .eq(IndexWeightDO::getTsCode, indexCode)
                .eq(IndexWeightDO::getTradeDate, tradeDate));

        if (rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<IndexWeightDO> batch : Lists.partition(rows, BATCH_SIZE)) {
            count += indexWeightMapper.insertBatch(batch);
        }
        return count;
    }
}
