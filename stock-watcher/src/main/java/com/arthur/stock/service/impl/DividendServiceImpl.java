package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.DividendDTO;
import com.arthur.stock.dto.tushare.DividendQueryDTO;
import com.arthur.stock.mapper.DividendMapper;
import com.arthur.stock.model.DividendDO;
import com.arthur.stock.service.DividendService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 分红送股服务实现类，负责从Tushare获取分红送股数据并持久化到本地数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendServiceImpl implements DividendService {

    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final DividendMapper dividendMapper;

    @Override
    public List<DividendDTO> queryByTsCode(String tsCode) {
        List<DividendDO> records = dividendMapper.selectList(
                new LambdaQueryWrapper<DividendDO>()
                        .eq(DividendDO::getTsCode, tsCode)
                        .orderByDesc(DividendDO::getEndDate));
        return records.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public List<DividendDTO> fetchAndSaveDividend(String tsCode) {
        log.info("Fetching dividend for {}", tsCode);

        DividendQueryDTO param = DividendQueryDTO.builder()
                .tsCode(tsCode)
                .build();
        List<DividendDTO> dividends = tushareClient.dividend(param);

        if (dividends.isEmpty()) {
            log.info("No dividend data returned for {}", tsCode);
            return Collections.emptyList();
        }

        List<DividendDO> entities = dividends.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveDividends(entities);
        log.info("Saved {} dividend records for {}", entities.size(), tsCode);
        return dividends;
    }

    @Override
    public List<DividendDTO> fetchAndSaveByAnnDate(String annDate) {
        log.info("Fetching dividend for ann_date={}", annDate);

        DividendQueryDTO param = DividendQueryDTO.builder()
                .annDate(annDate)
                .build();
        List<DividendDTO> dividends = tushareClient.dividend(param);

        if (dividends.isEmpty()) {
            log.info("No dividend data returned for ann_date={}", annDate);
            return Collections.emptyList();
        }

        List<DividendDO> entities = dividends.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        saveDividends(entities);
        log.info("Saved {} dividend records for ann_date={}", entities.size(), annDate);
        return dividends;
    }

    private DividendDO toEntity(DividendDTO dto) {
        return DividendDO.builder()
                .tsCode(dto.getTsCode())
                .endDate(dto.getEndDate())
                .annDate(dto.getAnnDate())
                .divProc(dto.getDivProc())
                .stkDiv(dto.getStkDiv())
                .stkBoRate(dto.getStkBoRate())
                .stkCoRate(dto.getStkCoRate())
                .cashDiv(dto.getCashDiv())
                .cashDivTax(dto.getCashDivTax())
                .recordDate(dto.getRecordDate())
                .exDate(dto.getExDate())
                .payDate(dto.getPayDate())
                .divListdate(dto.getDivListdate())
                .impAnnDate(dto.getImpAnnDate())
                .baseDate(dto.getBaseDate())
                .baseShare(dto.getBaseShare())
                .build();
    }

    private DividendDTO toDTO(DividendDO entity) {
        return DividendDTO.builder()
                .tsCode(entity.getTsCode())
                .endDate(entity.getEndDate())
                .annDate(entity.getAnnDate())
                .divProc(entity.getDivProc())
                .stkDiv(entity.getStkDiv())
                .stkBoRate(entity.getStkBoRate())
                .stkCoRate(entity.getStkCoRate())
                .cashDiv(entity.getCashDiv())
                .cashDivTax(entity.getCashDivTax())
                .recordDate(entity.getRecordDate())
                .exDate(entity.getExDate())
                .payDate(entity.getPayDate())
                .divListdate(entity.getDivListdate())
                .impAnnDate(entity.getImpAnnDate())
                .baseDate(entity.getBaseDate())
                .baseShare(entity.getBaseShare())
                .build();
    }

    /**
     * 批量保存分红数据。先删除同唯一键（ts_code+end_date+ann_date）已存在记录，再插入；跨方言通用。
     */
    private void saveDividends(List<DividendDO> dividends) {
        Lists.partition(dividends, BATCH_SIZE).forEach(batch -> {
            dividendMapper.deleteBatchByKeys(batch);
            dividendMapper.insertBatch(batch);
        });
    }
}