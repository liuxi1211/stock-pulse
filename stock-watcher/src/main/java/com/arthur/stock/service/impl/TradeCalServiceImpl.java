package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.ExchangeEnum;
import com.arthur.stock.constant.TradeDayStatusEnum;
import com.arthur.stock.mapper.TradeCalMapper;
import com.arthur.stock.model.TradeCalDO;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.dto.tushare.TradeCalQueryDTO;
import com.arthur.stock.service.TradeCalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 交易日历服务实现类，负责从Tushare获取交易日历数据并持久化到本地数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeCalServiceImpl implements TradeCalService {

    private static final int BATCH_SIZE = 500;

    private final TushareClient tushareClient;
    private final TradeCalMapper tradeCalMapper;

    /**
     * 从Tushare获取交易日历数据并保存到本地数据库，已存在的记录会更新
     */
    @Override
    public List<TradeCalDTO> fetchAndSaveTradeCal(String exchange, String startDate, String endDate) {
        log.info("Fetching trade_cal from Tushare: exchange={}, startDate={}, endDate={}", exchange, startDate, endDate);

        TradeCalQueryDTO param = TradeCalQueryDTO.builder()
                .exchange(exchange)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        List<TradeCalDTO> calendars = tushareClient.tradeCal(param);

        if (calendars.isEmpty()) {
            log.info("No trade_cal data returned");
            return Collections.emptyList();
        }

        List<TradeCalDO> entities = calendars.stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TradeCalDO::getCalDate))
                .collect(Collectors.toList());

        saveCalendars(entities);
        log.info("Saved {} trade_cal records", entities.size());
        return calendars;
    }

    /**
     * 从本地数据库查询交易日历，支持按交易所、日期范围、是否交易日筛选
     */
    @Override
    public List<TradeCalDTO> queryLocal(String exchange, String startDate, String endDate, String isOpen) {
        LambdaQueryWrapper<TradeCalDO> wrapper = new LambdaQueryWrapper<>();
        if (exchange != null && !exchange.isEmpty()) {
            wrapper.eq(TradeCalDO::getExchange, ExchangeEnum.fromCode(exchange));
        }
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(TradeCalDO::getCalDate, startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(TradeCalDO::getCalDate, endDate);
        }
        if (isOpen != null && !isOpen.isEmpty()) {
            wrapper.eq(TradeCalDO::getIsOpen, TradeDayStatusEnum.fromCode(isOpen));
        }
        wrapper.orderByAsc(TradeCalDO::getCalDate);

        List<TradeCalDO> calendars = tradeCalMapper.selectList(wrapper);
        return calendars.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private TradeCalDO toEntity(TradeCalDTO dto) {
        return TradeCalDO.builder()
                .exchange(ExchangeEnum.fromCode(dto.getExchange()))
                .calDate(dto.getCalDate())
                .isOpen(TradeDayStatusEnum.fromCode(dto.getIsOpen()))
                .pretradeDate(dto.getPretradeDate())
                .build();
    }

    private TradeCalDTO toDTO(TradeCalDO entity) {
        return TradeCalDTO.builder()
                .exchange(entity.getExchange() != null ? entity.getExchange().getCode() : null)
                .calDate(entity.getCalDate())
                .isOpen(entity.getIsOpen() != null ? entity.getIsOpen().getCode() : null)
                .pretradeDate(entity.getPretradeDate())
                .build();
    }

    /**
     * 批量保存交易日历数据。先删除同唯一键（exchange+cal_date）已存在记录，再插入；跨方言通用。
     */
    private void saveCalendars(List<TradeCalDO> calendars) {
        Lists.partition(calendars, BATCH_SIZE).forEach(batch -> {
            tradeCalMapper.deleteBatchByKeys(batch);
            tradeCalMapper.insertBatch(batch);
        });
    }
}