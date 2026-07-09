package com.arthur.stock.service;

import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.strategy.StrategyConfigUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyCreateRequest;
import com.arthur.stock.dto.strategy.StrategyDTO;
import com.arthur.stock.dto.strategy.StrategyDiffDTO;
import com.arthur.stock.dto.strategy.StrategyPageRequest;
import com.arthur.stock.dto.strategy.StrategyRollbackRequest;
import com.arthur.stock.dto.strategy.StrategyStatusUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyVersionDTO;

import java.util.List;

/**
 * 策略��理服务（spec 004 Task 6）。
 * <p>
 * 所有方法以 <b>strategyId（TEXT 业务 ID）</b> 为入参，内部先查主表 quant_strategy 拿 INTEGER PK，
 * 再操作 quant_strategy_version。版本快照表的 strategyId 字段存的是主表 PK（Long），不是业务 ID。
 * <p>
 * 关键约束：
 * <ul>
 *   <li>createStrategy / updateStrategyConfig / rollbackVersion 中 engineClient.validate
 *       调用都在<b>事务外</b>，避免长事务占用连接并保证 HTTP 失败不脏写。</li>
 *   <li>updateStrategyConfig 使用 expectedVersion 乐观锁；状态机自动转换 DRAFT→VERIFIED。</li>
 *   <li>所有时间字段用 Instant.now().toString()（UTC ISO8601）。</li>
 * </ul>
 */
public interface StrategyService {

    /**
     * 新建策略。configJson 非空则 engine 校验通过后 status=VERIFIED；为空则 status=DRAFT + 默认配置。
     */
    StrategyDTO createStrategy(StrategyCreateRequest req);

    /**
     * 分页查询策略。status 不传时默认排除 ARCHIVED。
     */
    PageResult<StrategyDTO> getStrategiesPage(StrategyPageRequest req);

    /**
     * 策略详情，含当前版本 config。
     */
    StrategyDTO getStrategyDetail(String strategyId);

    /**
     * 更新策略元信息（不改 config）。
     */
    void updateStrategy(String strategyId, StrategyUpdateRequest req);

    /**
     * 更新策略配置（生成新版本）。含长度校验、JSON 校验、乐观锁、engine 校验、状态机自动转换。
     */
    StrategyDTO updateStrategyConfig(String strategyId, StrategyConfigUpdateRequest req);

    /**
     * 软删除（status=ARCHIVED）。
     */
    void deleteStrategy(String strategyId);

    /**
     * 状态变更。校验 {@link com.arthur.stock.constant.StrategyStatusEnum#canTransitionTo}。
     */
    void updateStatus(String strategyId, StrategyStatusUpdateRequest req);

    /**
     * 版本列表（不含 configJson）。
     */
    List<StrategyVersionDTO> listVersions(String strategyId);

    /**
     * 单个版本详情（含 configJson）。
     */
    StrategyVersionDTO getVersion(String strategyId, Integer versionNo);

    /**
     * 版本对比。对 fromVer / toVer 的 configJson 做 JSON Diff。
     */
    List<StrategyDiffDTO> diffVersions(String strategyId, Integer fromVer, Integer toVer);

    /**
     * 回滚到指定版本。复用 updateStrategyConfig 链路：以目标版本 config 写新版本，
     * 重新走 engine 校验、乐观锁与状态机。
     */
    StrategyVersionDTO rollbackVersion(String strategyId, StrategyRollbackRequest req);

    /**
     * 策略统计聚合（按状态计数）。用于列表页统计条。
     */
    com.arthur.stock.dto.strategy.StrategyStatsDTO getStats();

    /**
     * 版本回测指标对比。当前回测数据未打通时返回空 metrics 列表。
     * @param strategyId 策略业务ID
     * @param fromVer    起始版本号
     * @param toVer      目标版本号
     */
    com.arthur.stock.dto.strategy.StrategyVersionCompareDTO compareVersions(String strategyId, Integer fromVer, Integer toVer);
}
