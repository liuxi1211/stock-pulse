package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.BlockTradePremiumVO;
import com.arthur.stock.dto.BlockTradeWithCloseVO;
import com.arthur.stock.dto.tushare.BlockTradeDTO;
import com.arthur.stock.mapper.BlockTradeMapper;
import com.arthur.stock.model.BlockTradeDO;
import com.arthur.stock.service.BlockTradeService;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class BlockTradeServiceImpl implements BlockTradeService {

    /** 批量写入批次大小 */
    private static final int BATCH_SIZE = 500;

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
}
