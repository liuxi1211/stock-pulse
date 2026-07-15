package com.arthur.stock.dto.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 内部接口：point-in-time 成分股查询响应（spec 010 缺陷 A 修复）。
 * <p>
 * effective_date 为实际生效日（≤ trade_date 的最新快照交易日）；
 * 查询日早于最早快照时 constituents 为空、effective_date 为 null、warning 非空。
 */
@Data
@Builder
@Schema(description = "PIT 成分股查询响应")
public class ConstituentsQueryResponseDTO {

    @Schema(description = "指数代码（回显请求）")
    private String index_code;

    @Schema(description = "查询日（回显请求）")
    private String trade_date;

    @Schema(description = "实际生效日（≤ trade_date 的最新快照交易日）；无快照时为 null")
    private String effective_date;

    @Schema(description = "成分股代码列表；无快照时为空列表")
    private List<String> constituents;

    @Schema(description = "告警信息；查询日早于最早快照时填充：no snapshot before this date")
    private String warning;
}
