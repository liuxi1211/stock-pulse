package com.arthur.stock.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 选股 snapshot 执行结果（engine {@code data} 反序列化），与 engine 返回结构对齐。
 * <p>
 * 由 {@link com.arthur.stock.client.ScreenerClient} 把 engine 信封里的 {@code data} 节点反序列化为本对象。
 */
@Data
public class SnapshotResultVO {

    /** 选股日，格式 YYYY-MM-DD */
    private String date;

    /** 命中股票总数 */
    private Integer totalCount;

    /** 命中股票列表（按 rank 升序） */
    private List<StockResultVO> stocks;

    /** 被剔除原因：reason -> [symbol...]（仅 verboseExcluded=true 时有值） */
    private Map<String, List<String>> excluded;
}
