package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.AdjFactorDTO;
import com.arthur.stock.dto.tushare.AdjFactorQueryDTO;
import com.arthur.stock.mapper.AdjFactorMapper;
import com.arthur.stock.model.AdjFactorDO;
import com.arthur.stock.service.AdjFactorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 复权因子服务实现类，负责从Tushare获取复权因子数据并持久化到本地数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdjFactorServiceImpl implements AdjFactorService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final AdjFactorMapper adjFactorMapper;

    @Override
    public List<AdjFactorDTO> queryByCodeAndDateRange(String tsCode, String startDate, String endDate) {
        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        return tushareClient.adjFactor(param);
    }

    @Override
    public List<AdjFactorDTO> queryByTradeDate(String tradeDate) {
        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tradeDate(tradeDate)
                .build();
        return tushareClient.adjFactor(param);
    }

    @Override
    public List<AdjFactorDTO> fetchAndSaveAdjFactor(String tsCode) {
        String lastDate = getLastTradeDate(tsCode);

        String startDate;
        if (lastDate != null) {
            LocalDate ld = LocalDate.parse(lastDate, DATE_FMT);
            startDate = ld.plusDays(1).format(DATE_FMT);
        } else {
            startDate = LocalDate.now().minusYears(30).format(DATE_FMT);
        }

        String endDate = LocalDate.now().format(DATE_FMT);

        if (startDate.compareTo(endDate) > 0) {
            log.info("Stock {} adj_factor is up to date", tsCode);
            return Collections.emptyList();
        }

        log.info("Fetching adj_factor for {} from {} to {}", tsCode, startDate, endDate);

        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<AdjFactorDTO> factors = tushareClient.adjFactor(param);

        if (factors.isEmpty()) {
            log.info("No adj_factor data returned for {}", tsCode);
            return Collections.emptyList();
        }

        List<AdjFactorDO> entities = factors.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveAdjFactors(entities);
        log.info("Saved {} adj_factor records for {}", entities.size(), tsCode);
        return factors;
    }

    @Override
    public List<AdjFactorDTO> fetchAndSaveByTradeDate(String tradeDate) {
        log.info("Fetching adj_factor for trade_date={}", tradeDate);

        AdjFactorQueryDTO param = AdjFactorQueryDTO.builder()
                .tradeDate(tradeDate)
                .build();
        List<AdjFactorDTO> factors = tushareClient.adjFactor(param);

        if (factors.isEmpty()) {
            log.info("No adj_factor data returned for trade_date={}", tradeDate);
            return Collections.emptyList();
        }

        List<AdjFactorDO> entities = factors.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveAdjFactors(entities);
        log.info("Saved {} adj_factor records for trade_date={}", entities.size(), tradeDate);
        return factors;
    }

    private String getLastTradeDate(String tsCode) {
        AdjFactorDO last = adjFactorMapper.selectOne(
                new LambdaQueryWrapper<AdjFactorDO>()
                        .eq(AdjFactorDO::getTsCode, tsCode)
                        .orderByDesc(AdjFactorDO::getTradeDate)
                        .last("LIMIT 1"));
        return last != null ? last.getTradeDate() : null;
    }

    /**
     * 从本地数据库查询指定股票的全部复权因子（按日期升序）
     */
    @Override
    public List<AdjFactorDO> queryLocalByTsCode(String tsCode) {
        return adjFactorMapper.selectList(
                new LambdaQueryWrapper<AdjFactorDO>()
                        .eq(AdjFactorDO::getTsCode, tsCode)
                        .orderByAsc(AdjFactorDO::getTradeDate));
    }

    private AdjFactorDO toEntity(AdjFactorDTO dto) {
        return AdjFactorDO.builder()
                .tsCode(dto.getTsCode())
                .tradeDate(dto.getTradeDate())
                .adjFactor(dto.getAdjFactor())
                .build();
    }

    /**
     * 批量保存复权因子数据。先删除同主键（ts_code+trade_date）已存在记录，再插入；跨方言通用。
     */
    private void saveAdjFactors(List<AdjFactorDO> factors) {
        Lists.partition(factors, BATCH_SIZE).forEach(batch -> {
            adjFactorMapper.deleteBatchByKeys(batch);
            adjFactorMapper.insertBatch(batch);
        });
    }
}