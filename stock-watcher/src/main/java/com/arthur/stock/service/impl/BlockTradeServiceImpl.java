package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.BlockTradePremiumVO;
import com.arthur.stock.dto.BlockTradeWithCloseVO;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.BlockTradeDTO;
import com.arthur.stock.mapper.BlockTradeMapper;
import com.arthur.stock.model.BlockTradeDO;
import com.arthur.stock.service.BlockTradeService;
import com.arthur.stock.service.DataCheckable;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 大宗交易服务实现。
 * <p>
 * 数据源：tushare block_trade（doc_id=160）。
 * 落库策略：按主键 (trade_date, ts_code, buyer, seller) 批量 delete-then-insert，保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockTradeServiceImpl implements BlockTradeService, DataCheckable {

    /** 批量写入批次大小 */
    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final BlockTradeMapper blockTradeMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int fetchAndSave(String tradeDate) {
        log.info("Fetching block_trade for tradeDate={}", tradeDate);
        List<BlockTradeDTO> rows = tushareClient.blockTrade(tradeDate, null);
        if (rows.isEmpty()) {
            log.info("block_trade returned 0 records for {}", tradeDate);
            return 0;
        }
        List<BlockTradeDO> entities = rows.stream()
                .map(this::toEntity)
                .filter(e -> e != null)
                .toList();
        int count = 0;
        for (List<BlockTradeDO> batch : Lists.partition(entities, BATCH_SIZE)) {
            blockTradeMapper.deleteBatchByKeys(batch);
            count += blockTradeMapper.insertBatch(batch);
        }
        log.info("Saved {} block_trade records for {}", count, tradeDate);
        return count;
    }

    @Override
    public List<BlockTradeWithCloseVO> queryPage(String tradeDate, int page, int size, String sortBy, String order) {
        int offset = (page - 1) * size;
        return blockTradeMapper.selectPage(tradeDate, offset, size, sortBy, order);
    }

    @Override
    public int countPage(String tradeDate) {
        return blockTradeMapper.countByTradeDate(tradeDate);
    }

    @Override
    public List<BlockTradePremiumVO> queryPremiumDistribution(String tradeDate) {
        List<BlockTradeWithCloseVO> rows = blockTradeMapper.selectAllWithCloseByTradeDate(tradeDate);
        // 8 个折溢价区间（顺序固定）
        String[] ranges = {"<-5%", "-5%~-3%", "-3%~-1%", "-1%~0%", "0%~1%", "1%~3%", "3%~5%", ">5%"};
        int[] counts = new int[ranges.length];

        for (BlockTradeWithCloseVO row : rows) {
            BigDecimal closePrice = row.getClosePrice();
            BigDecimal price = row.getPrice();
            if (closePrice == null || closePrice.compareTo(BigDecimal.ZERO) == 0 || price == null) {
                continue;
            }
            double premium = price.subtract(closePrice)
                    .divide(closePrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
            int idx = bucketIndex(premium);
            counts[idx]++;
        }

        List<BlockTradePremiumVO> result = new ArrayList<>(ranges.length);
        for (int i = 0; i < ranges.length; i++) {
            result.add(new BlockTradePremiumVO(ranges[i], counts[i]));
        }
        return result;
    }

    @Override
    public String getLatestTradeDate() {
        return blockTradeMapper.selectLatestTradeDate();
    }

    // ==================== 内部方法 ====================

    private BlockTradeDO toEntity(BlockTradeDTO dto) {
        if (dto == null || dto.getTradeDate() == null || dto.getTsCode() == null
                || dto.getBuyer() == null || dto.getSeller() == null) {
            return null;
        }
        return BlockTradeDO.builder()
                .tradeDate(dto.getTradeDate())
                .tsCode(dto.getTsCode())
                .name(dto.getName())
                .price(dto.getPrice())
                .vol(dto.getVol())
                .amount(dto.getAmount())
                .buyer(dto.getBuyer())
                .seller(dto.getSeller())
                .buyerName(dto.getBuyerName())
                .sellerName(dto.getSellerName())
                .build();
    }

    /**
     * 折溢价率分桶索引：
     * 0: &lt;-5%, 1: -5%~-3%, 2: -3%~-1%, 3: -1%~0%,
     * 4: 0%~1%, 5: 1%~3%, 6: 3%~5%, 7: &gt;=5%
     */
    private int bucketIndex(double premium) {
        if (premium < -5) return 0;
        if (premium < -3) return 1;
        if (premium < -1) return 2;
        if (premium < 0) return 3;
        if (premium < 1) return 4;
        if (premium < 3) return 5;
        if (premium < 5) return 6;
        return 7;
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.BLOCK_TRADE.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = blockTradeMapper.selectCount(null);
            String latestDate = blockTradeMapper.selectLatestTradeDate();

            if (totalRows == 0) {
                items.add(DataCheckItem.builder()
                        .name("price_vol_validity")
                        .displayName("价量有效性检测")
                        .passed(true)
                        .level(CheckLevel.ERROR)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("buyer_seller_same")
                        .displayName("买卖方一致性检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
                items.add(DataCheckItem.builder()
                        .name("amount_calculation")
                        .displayName("成交额计算检测")
                        .passed(true)
                        .level(CheckLevel.WARN)
                        .message("表为空，跳过检测")
                        .build());
            } else {
                String thirtyDaysAgo = LocalDate.now().minusDays(30).format(DATE_FMT);

                int invalidPriceVol = blockTradeMapper.countInvalidPriceVol(thirtyDaysAgo);
                items.add(DataCheckItem.builder()
                        .name("price_vol_validity")
                        .displayName("价量有效性检测")
                        .passed(invalidPriceVol == 0)
                        .level(CheckLevel.ERROR)
                        .message(invalidPriceVol == 0 ? "通过，最近30天价量数据正常"
                                : "最近30天价格/成交量/成交额小于等于0的记录 " + invalidPriceVol + " 条")
                        .build());

                int buyerSellerSame = blockTradeMapper.countBuyerSellerSame(thirtyDaysAgo);
                items.add(DataCheckItem.builder()
                        .name("buyer_seller_same")
                        .displayName("买卖方一致性检测")
                        .passed(buyerSellerSame == 0)
                        .level(CheckLevel.WARN)
                        .message(buyerSellerSame == 0 ? "通过，最近30天买卖方均不同"
                                : "最近30天买卖方相同的记录 " + buyerSellerSame + " 条")
                        .build());

                int amountCalculationError = blockTradeMapper.countAmountCalculationError(thirtyDaysAgo);
                items.add(DataCheckItem.builder()
                        .name("amount_calculation")
                        .displayName("成交额计算检测")
                        .passed(amountCalculationError == 0)
                        .level(CheckLevel.WARN)
                        .message(amountCalculationError == 0 ? "通过，最近30天成交额计算正常"
                                : "最近30天成交额与价量乘积偏差超过10%的记录 " + amountCalculationError + " 条")
                        .build());
            }

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.BLOCK_TRADE.getLabel())
                    .totalRows(totalRows)
                    .latestDate(latestDate)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for block_trade", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + e.getMessage())
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.BLOCK_TRADE.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
