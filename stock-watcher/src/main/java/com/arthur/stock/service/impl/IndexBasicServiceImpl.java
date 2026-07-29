package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.constant.InitStep;
import com.arthur.stock.dto.governance.CheckLevel;
import com.arthur.stock.dto.governance.DataCheckItem;
import com.arthur.stock.dto.governance.DataCheckResult;
import com.arthur.stock.dto.tushare.IndexBasicDTO;
import com.arthur.stock.dto.tushare.IndexBasicQueryDTO;
import com.arthur.stock.mapper.IndexBasicMapper;
import com.arthur.stock.model.IndexBasicDO;
import com.arthur.stock.service.IndexBasicService;
import com.arthur.stock.util.SensitiveDataUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 指数基本信息服务实现。
 * <p>
 * 数据源：tushare index_basic 接口（doc_id=94）。
 * 落库策略：全量替换（deleteAll + 分批 insertBatch），index_basic 是低频变更的维度表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexBasicServiceImpl implements IndexBasicService {

    private static final int DB_BATCH_SIZE = 500;
    private static final int BATCH_SIZE = 5000;
    private static final int MAX_PAGES = 10;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final IndexBasicMapper indexBasicMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 全市场全量拉取并替换落库。
     * <p>
     * index_basic 不传 market 参数时返回全部市场指数（约数千条），单次分页 5000 即可取完。
     * 落库采用 deleteAll + insertBatch（全量替换），保证与 Tushare 侧一致。
     */
    @Override
    public int fetchAndSaveAll() {
        log.info("Fetching index_basic (all markets) from Tushare");
        IndexBasicQueryDTO param = IndexBasicQueryDTO.builder().build();
        return fetchAndSave(param, "all-markets");
    }

    @Override
    public int fetchAndSaveByMarket(String market) {
        log.info("Fetching index_basic for market={}", market);
        IndexBasicQueryDTO param = IndexBasicQueryDTO.builder().market(market).build();
        return fetchAndSave(param, market);
    }

    private int fetchAndSave(IndexBasicQueryDTO param, String scope) {
        int total = 0;
        List<IndexBasicDO> allEntities = new ArrayList<>();
        int offset = 0;
        int page = 0;
        while (true) {
            page++;
            if (page > MAX_PAGES) {
                log.warn("index_basic {} exceeded MAX_PAGES={}, total so far={}", scope, MAX_PAGES, total);
                break;
            }
            List<IndexBasicDTO> rows = tushareClient.indexBasic(param, offset, BATCH_SIZE);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            List<IndexBasicDO> entities = rows.stream()
                    .map(this::toEntity)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            allEntities.addAll(entities);
            total += rows.size();
            log.info("index_basic {} page={} offset={} fetched {} records (total={})",
                    scope, page, offset, rows.size(), total);
            if (rows.size() < BATCH_SIZE) {
                break;
            }
            offset += BATCH_SIZE;
        }

        if (total == 0) {
            log.info("No index_basic data returned for {}", scope);
            return 0;
        }

        int saved = allEntities.size();
        saveAll(allEntities);
        log.info("Saved {} index_basic records for {} (fetched={})", saved, scope, total);
        return saved;
    }

    @Override
    public List<IndexBasicDTO> queryLocal(String market, String category, String keyword) {
        LambdaQueryWrapper<IndexBasicDO> wrapper = new LambdaQueryWrapper<>();
        if (market != null && !market.isEmpty()) {
            wrapper.eq(IndexBasicDO::getMarket, market);
        }
        if (category != null && !category.isEmpty()) {
            wrapper.eq(IndexBasicDO::getCategory, category);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(IndexBasicDO::getTsCode, keyword)
                    .or().like(IndexBasicDO::getName, keyword));
        }
        wrapper.orderByAsc(IndexBasicDO::getMarket).orderByAsc(IndexBasicDO::getTsCode);
        List<IndexBasicDO> list = indexBasicMapper.selectList(wrapper);
        return list.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private IndexBasicDO toEntity(IndexBasicDTO dto) {
        if (dto == null || dto.getTsCode() == null) {
            return null;
        }
        return IndexBasicDO.builder()
                .tsCode(dto.getTsCode())
                .name(dto.getName())
                .fullname(dto.getFullname())
                .market(dto.getMarket())
                .publisher(dto.getPublisher())
                .indexType(dto.getIndexType())
                .category(dto.getCategory())
                .baseDate(dto.getBaseDate())
                .basePoint(dto.getBasePoint())
                .listDate(dto.getListDate())
                .weightRule(dto.getWeightRule())
                .build();
    }

    private IndexBasicDTO toDTO(IndexBasicDO e) {
        return IndexBasicDTO.builder()
                .tsCode(e.getTsCode())
                .name(e.getName())
                .fullname(e.getFullname())
                .market(e.getMarket())
                .publisher(e.getPublisher())
                .indexType(e.getIndexType())
                .category(e.getCategory())
                .baseDate(e.getBaseDate())
                .basePoint(e.getBasePoint())
                .listDate(e.getListDate())
                .weightRule(e.getWeightRule())
                .build();
    }

    /**
     * 全量替换落库：TRUNCATE + 分批 insertBatch。
     * <p>
     * TRUNCATE 是 DDL，会隐式提交且无法回滚，不能与 INSERT 放在同一事务中。
     * 因此先 TRUNCATE（自动提交），再在事务内批量 INSERT：
     * <ul>
     *   <li>TRUNCATE 极快，且会重置自增 ID（index_basic 下游不依赖具体 id 值，重置无影响）；</li>
     *   <li>批量 INSERT 仍在事务中，单批失败会回滚该批并向上抛出，由定时任务下次重试。</li>
     * </ul>
     * 极端场景：TRUNCATE 成功但 INSERT 全部失败 → 表为空，下次全量同步会重新填满，可接受。
     */
    private void saveAll(List<IndexBasicDO> entities) {
        indexBasicMapper.truncateTable();
        transactionTemplate.execute(status -> {
            Lists.partition(entities, DB_BATCH_SIZE).forEach(indexBasicMapper::insertBatch);
            return null;
        });
    }

    // ==================== DataCheckable ====================

    @Override
    public String getTableCode() {
        return InitStep.INDEX_BASIC.getCode();
    }

    @Override
    public DataCheckResult checkData() {
        List<DataCheckItem> items = new ArrayList<>();
        try {
            long totalRows = indexBasicMapper.selectCount(null);

            // Check 1: 关键字段空值检测（ts_code 由 NOT NULL 约束保证，这里查 name/market）
            long nullMarketCount = indexBasicMapper.selectCount(
                    new LambdaQueryWrapper<IndexBasicDO>().isNull(IndexBasicDO::getMarket));
            items.add(DataCheckItem.builder()
                    .name("null_market")
                    .displayName("市场字段空值检测")
                    .passed(nullMarketCount == 0)
                    .level(CheckLevel.WARN)
                    .message(nullMarketCount == 0 ? "通过，无 market 为空记录"
                            : "存在 " + nullMarketCount + " 条 market 为空的记录")
                    .build());

            // Check 2: 申万一级行业指数覆盖检测
            long swCount = indexBasicMapper.selectCount(
                    new LambdaQueryWrapper<IndexBasicDO>().eq(IndexBasicDO::getMarket, "SW"));
            boolean swPassed = swCount > 0;
            items.add(DataCheckItem.builder()
                    .name("sw_coverage")
                    .displayName("申万指数覆盖检测")
                    .passed(swPassed)
                    .level(CheckLevel.ERROR)
                    .message(swPassed ? "通过，申万指数 " + swCount + " 条"
                            : "申万指数（market=SW）为空，板块行情将缺失数据")
                    .build());

            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.INDEX_BASIC.getLabel())
                    .totalRows(totalRows)
                    .latestDate(LocalDate.now().format(DATE_FMT))
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.error("checkData error for index_basic", e);
            items.add(DataCheckItem.builder()
                    .name("error")
                    .displayName("检测执行异常")
                    .passed(false)
                    .level(CheckLevel.ERROR)
                    .message("检测执行异常: " + SensitiveDataUtil.mask(e.getMessage()))
                    .build());
            return DataCheckResult.builder()
                    .tableCode(getTableCode())
                    .tableName(InitStep.INDEX_BASIC.getLabel())
                    .totalRows(0)
                    .latestDate(null)
                    .items(items)
                    .build();
        }
    }
}
