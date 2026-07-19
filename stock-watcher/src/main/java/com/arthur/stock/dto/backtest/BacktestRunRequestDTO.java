package com.arthur.stock.dto.backtest;

import lombok.Data;

import java.util.Map;

/**
 * 回测运行请求 DTO（spec 007 T3）。
 * <p>
 * 第一波 mode 固定 SINGLE；overrideConfig 为前端对策略默认参数的覆盖（如 initial_cash / broker_profile）。
 * benchmark 为基准指数代码，可空（为空时按 config_json.backtest_config.benchmark → 默认 000300.SH 兜底）。
 */
@Data
public class BacktestRunRequestDTO {

    /** 回测模式（第一波仅 SINGLE） */
    private String mode;

    /** 策略UUID（前端传入，后端查主表拿 id） */
    private String uuid;

    /** 策略版本号；为空时取策略 currentVersion */
    private Integer versionNo;

    /** 参数覆盖配置（key→value，透传给 engine） */
    private Map<String, Object> overrideConfig;

    /** 基准指数代码（可空） */
    private String benchmark;
}
