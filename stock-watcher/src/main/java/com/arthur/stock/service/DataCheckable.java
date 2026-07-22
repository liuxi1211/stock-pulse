package com.arthur.stock.service;

import com.arthur.stock.dto.governance.DataCheckResult;

/**
 * 数据校验接口。每张业务表的 Service 想接入数据管控，就实现这个接口。
 */
public interface DataCheckable {
    /** 执行校验，返回所有检测项结果（含通过的和不通过的） */
    DataCheckResult checkData();
    /** 表代码，对应 InitStep.code */
    String getTableCode();
}
