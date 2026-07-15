package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.internal.ConstituentsQueryRequestDTO;
import com.arthur.stock.dto.internal.ConstituentsQueryResponseDTO;
import com.arthur.stock.service.IndexWeightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * 内部接口 Controller（spec 010 缺陷 A 修复）。
 * <p>
 * 供 stock-engine 等内部服务在回测/选股时调用，提供 point-in-time（PIT）数据查询，
 * 用于消除幸存者偏差。与用户态接口隔离，走 {@code /api/internal/**} 命名空间。
 * <p>
 * <b>鉴权</b>：无。watcher 与 engine 同机部署，engine 经 {@code http://localhost:<port>}
 * （端口可变，由 engine 侧 {@code WATCHER_BASE_URL} 指定）调用本接口，仅监听本机，
 * 不做 token/session 校验。若后续拆分到不同机器，需补网络隔离或鉴权。
 */
@Tag(name = "内部接口", description = "服务间内部调用：PIT 成分股查询等，仅限本机")
@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalController {

    private static final String NO_SNAPSHOT_WARNING = "no snapshot before this date";

    private final IndexWeightService indexWeightService;

    /**
     * point-in-time 成分股查询：取 ≤ trade_date 的最新指数成分股快照。
     * <p>
     * 用于 engine 回测/选股时按查询日还原当时真实的指数成分，避免用最新成分股
     * 回测历史区段产生的幸存者偏差。
     * <p>
     * 查询日早于最早快照时返回空 constituents + effective_date=null + warning。
     */
    @Operation(summary = "PIT 成分股查询", description = "按查询日取 ≤ 该日的最新指数成分股快照，防幸存者偏差")
    @PostMapping("/constituents/query")
    public ResponseEntity<ApiResponse<ConstituentsQueryResponseDTO>> queryConstituents(
            @Valid @RequestBody ConstituentsQueryRequestDTO req) {

        String indexCode = req.getIndex_code();
        String tradeDate = req.getTrade_date();

        String effectiveDate = indexWeightService.getEffectiveDate(indexCode, tradeDate);
        List<String> constituents = effectiveDate == null
                ? Collections.emptyList()
                : indexWeightService.getConstituentsAt(indexCode, tradeDate);

        ConstituentsQueryResponseDTO resp = ConstituentsQueryResponseDTO.builder()
                .index_code(indexCode)
                .trade_date(tradeDate)
                .effective_date(effectiveDate)
                .constituents(constituents)
                .warning(effectiveDate == null ? NO_SNAPSHOT_WARNING : null)
                .build();

        log.info("Internal constituents query: indexCode={}, tradeDate={}, effectiveDate={}, size={}",
                indexCode, tradeDate, effectiveDate, constituents.size());
        return ResponseEntity.ok(ApiResponse.success(resp));
    }
}
