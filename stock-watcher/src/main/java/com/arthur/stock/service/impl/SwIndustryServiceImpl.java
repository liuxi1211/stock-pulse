package com.arthur.stock.service.impl;

import com.arthur.stock.client.TushareClient;
import com.arthur.stock.dto.tushare.IndexClassifyDTO;
import com.arthur.stock.dto.tushare.IndexClassifyQueryDTO;
import com.arthur.stock.dto.tushare.IndexMemberDTO;
import com.arthur.stock.dto.tushare.IndexMemberQueryDTO;
import com.arthur.stock.mapper.SwIndustryMapper;
import com.arthur.stock.mapper.SwIndustryMemberMapper;
import com.arthur.stock.model.SwIndustryDO;
import com.arthur.stock.model.SwIndustryMemberDO;
import com.arthur.stock.service.SwIndustryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 申万行业分类服务实现。
 * <p>
 * 数据源：tushare index_classify（doc_id=181）+ index_member_all（doc_id=335），按 SWS2021 版本。
 * 落库策略：
 * <ul>
 *   <li>分类：按 src 先删后插（全量替换）；</li>
 *   <li>成分股：按 (ts_code, index_code, update_date) 业务键先删后插，实现幂等 upsert。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwIndustryServiceImpl implements SwIndustryService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** index_member_all 单次分页大小（Tushare 上限 2000） */
    private static final int MEMBER_PAGE_SIZE = 2000;

    /** 成分股批量插入批次大小 */
    private static final int INSERT_BATCH = 500;

    private final TushareClient tushareClient;
    private final SwIndustryMapper swIndustryMapper;
    private final SwIndustryMemberMapper swIndustryMemberMapper;

    @Override
    public int fetchAndSaveClassify(String src) {
        String effectiveSrc = src == null ? "SWS2021" : src;
        log.info("Fetching sw_industry classify: src={}", effectiveSrc);

        IndexClassifyQueryDTO param = IndexClassifyQueryDTO.builder()
                .src(effectiveSrc)
                .build();
        List<IndexClassifyDTO> rows = tushareClient.indexClassify(param);
        if (rows.isEmpty()) {
            log.info("No sw_industry classify data for src={}", effectiveSrc);
            return 0;
        }

        // 按 src 全量替换
        swIndustryMapper.delete(new LambdaQueryWrapper<SwIndustryDO>()
                .eq(SwIndustryDO::getSrc, effectiveSrc));

        int count = 0;
        for (IndexClassifyDTO dto : rows) {
            SwIndustryDO entity = toClassifyEntity(dto, effectiveSrc);
            if (entity == null) {
                continue;
            }
            swIndustryMapper.insert(entity);
            count++;
        }
        log.info("Saved {} sw_industry classify records for src={}", count, effectiveSrc);
        return count;
    }

    @Override
    public int fetchAndSaveMembers(String indexCode, String src) {
        String effectiveSrc = src == null ? "SWS2021" : src;
        log.info("Fetching sw_industry_member by index: indexCode={}, src={}", indexCode, effectiveSrc);

        IndexMemberQueryDTO param = IndexMemberQueryDTO.builder()
                .indexCode(indexCode)
                .src(effectiveSrc)
                .build();
        List<IndexMemberDTO> rows = tushareClient.indexMemberAll(param);
        return persistMembers(rows, effectiveSrc);
    }

    @Override
    public int fetchAndSaveAllMembers(String src) {
        String effectiveSrc = src == null ? "SWS2021" : src;
        log.info("Fetching sw_industry_member (paginated, size={}): src={}", MEMBER_PAGE_SIZE, effectiveSrc);

        int total = 0;
        int offset = 0;
        String today = LocalDate.now().format(DATE_FMT);
        while (true) {
            IndexMemberQueryDTO param = IndexMemberQueryDTO.builder()
                    .src(effectiveSrc)
                    .build();
            List<IndexMemberDTO> page = tushareClient.indexMemberAll(param, offset, MEMBER_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            // 接口未返回 update_date，persistMembers 用指定 today 作为同步快照日
            total += persistMembers(page, effectiveSrc, today);
            log.info("sw_industry_member page fetched: offset={}, size={}, total={}", offset, page.size(), total);

            if (page.size() < MEMBER_PAGE_SIZE) {
                break;
            }
            offset += MEMBER_PAGE_SIZE;
        }
        log.info("Saved {} sw_industry_member records for src={}", total, effectiveSrc);
        return total;
    }

    @Override
    public String getLatestL1Industry(String tsCode) {
        SwIndustryMemberDO m = swIndustryMemberMapper.selectLatestL1ByTsCode(tsCode);
        return m == null ? null : m.getIndexCode();
    }

    @Override
    public Map<String, String> getLatestL1Industries(List<String> tsCodes) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SwIndustryMemberDO> rows = swIndustryMemberMapper.selectLatestL1ByTsCodes(tsCodes);
        Map<String, String> result = new HashMap<>(rows.size());
        for (SwIndustryMemberDO m : rows) {
            result.put(m.getTsCode(), m.getIndexCode());
        }
        return result;
    }

    @Override
    public String getL1IndustryAt(String tsCode, String tradeDate) {
        SwIndustryMemberDO m = swIndustryMemberMapper.selectL1AtDate(tsCode, tradeDate);
        return m == null ? null : m.getIndexCode();
    }

    // ==================== 内部方法 ====================

    private SwIndustryDO toClassifyEntity(IndexClassifyDTO dto, String src) {
        if (dto == null || dto.getIndexCode() == null) {
            return null;
        }
        return SwIndustryDO.builder()
                .indexCode(dto.getIndexCode())
                .indexName(dto.getIndexName())
                .level(dto.getLevel())
                .parentCode(dto.getParentCode())
                .src(src)
                .build();
    }

    private SwIndustryMemberDO toMemberEntity(IndexMemberDTO dto, String src, String updateDate) {
        if (dto == null || dto.getTsCode() == null || dto.getIndexCode() == null) {
            return null;
        }
        return SwIndustryMemberDO.builder()
                .tsCode(dto.getTsCode())
                .indexCode(dto.getIndexCode())
                .indexName(dto.getIndexName())
                .inDate(dto.getInDate())
                .outDate(dto.getOutDate())
                .isNew(dto.getIsNew())
                .src(src)
                .updateDate(updateDate)
                .build();
    }

    /**
     * 成分股落库：update_date 取 in_date（无则 out_date，再无则当天）。
     */
    private int persistMembers(List<IndexMemberDTO> rows, String src) {
        if (rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (IndexMemberDTO dto : rows) {
            String updateDate = firstNonBlank(dto.getInDate(), dto.getOutDate(),
                    LocalDate.now().format(DATE_FMT));
            SwIndustryMemberDO entity = toMemberEntity(dto, src, updateDate);
            if (entity == null) {
                continue;
            }
            saveMember(entity);
            count++;
        }
        return count;
    }

    /**
     * 成分股落库（指定 updateDate），按 (ts_code, index_code, update_date) 先删后插。
     */
    private int persistMembers(List<IndexMemberDTO> rows, String src, String updateDate) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<SwIndustryMemberDO> entities = rows.stream()
                .map(dto -> toMemberEntity(dto, src, updateDate))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        int count = 0;
        for (int i = 0; i < entities.size(); i += INSERT_BATCH) {
            List<SwIndustryMemberDO> batch = entities.subList(i, Math.min(i + INSERT_BATCH, entities.size()));
            for (SwIndustryMemberDO row : batch) {
                saveMember(row);
                count++;
            }
        }
        return count;
    }

    private void saveMember(SwIndustryMemberDO row) {
        swIndustryMemberMapper.delete(new LambdaQueryWrapper<SwIndustryMemberDO>()
                .eq(SwIndustryMemberDO::getTsCode, row.getTsCode())
                .eq(SwIndustryMemberDO::getIndexCode, row.getIndexCode())
                .eq(SwIndustryMemberDO::getUpdateDate, row.getUpdateDate()));
        swIndustryMemberMapper.insert(row);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return LocalDate.now().format(DATE_FMT);
    }

    @Override
    public Map<String, Map<String, String>> getL1IndustriesPit(List<String> tsCodes, String startDate, String endDate) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        // 1. 一次性查全部历史一级成分股记录（按 ts_code, update_date 升序）
        List<SwIndustryMemberDO> all = swIndustryMemberMapper.selectAllL1HistoryByTsCodes(tsCodes);
        // 2. 按 ts_code 分组（已按 update_date 升序）
        Map<String, List<SwIndustryMemberDO>> byCode = all.stream()
                .collect(Collectors.groupingBy(SwIndustryMemberDO::getTsCode));
        Map<String, Map<String, String>> result = new HashMap<>();
        if (startDate == null || endDate == null) {
            // 无区间约束：退化为按 update_date 索引的 map（调用方应总是传区间）
            for (Map.Entry<String, List<SwIndustryMemberDO>> e : byCode.entrySet()) {
                Map<String, String> inner = new HashMap<>();
                for (SwIndustryMemberDO m : e.getValue()) {
                    inner.put(m.getUpdateDate(), m.getIndexCode());
                }
                if (!inner.isEmpty()) {
                    result.put(e.getKey(), inner);
                }
            }
            return result;
        }
        // 3. 有区间：穷举 [startDate, endDate] 的自然日，对每个 ts_code forward-fill
        List<String> dates = enumerateDates(startDate, endDate);
        for (Map.Entry<String, List<SwIndustryMemberDO>> e : byCode.entrySet()) {
            List<SwIndustryMemberDO> records = e.getValue();  // update_date 升序
            Map<String, String> inner = new HashMap<>();
            int idx = -1;  // 当前生效记录的下标
            for (String d : dates) {
                // 推进 idx 到最后一个 update_date <= d 的记录
                while (idx + 1 < records.size()) {
                    String ud = records.get(idx + 1).getUpdateDate();
                    if (ud != null && ud.compareTo(d) <= 0) {
                        idx++;
                    } else {
                        break;
                    }
                }
                if (idx >= 0) {
                    inner.put(d, records.get(idx).getIndexCode());
                }
                // idx < 0：该日早于任何 update_date → 不下发（engine 静默跳过）
            }
            if (!inner.isEmpty()) {
                result.put(e.getKey(), inner);
            }
        }
        return result;
    }

    /** 枚举 [startDate, endDate] 区间内的自然日（yyyyMMdd），含两端；解析失败返回空。 */
    private static List<String> enumerateDates(String startDate, String endDate) {
        List<String> out = new ArrayList<>();
        try {
            LocalDate s = LocalDate.parse(startDate, DATE_FMT);
            LocalDate e = LocalDate.parse(endDate, DATE_FMT);
            for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
                out.add(d.format(DATE_FMT));
            }
        } catch (Exception ignore) {
            // 日期解析失败 → 返回空（不 forward-fill）
        }
        return out;
    }
}
