package com.arthur.stock.service;

import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.backtest.BacktestCompareVO;
import com.arthur.stock.dto.backtest.BacktestReportVO;
import com.arthur.stock.dto.backtest.BacktestRunRequestDTO;
import com.arthur.stock.dto.backtest.BacktestTaskVO;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * 回测中心编排服务（spec 007 T3）。
 * <p>
 * 负责：任务编排（异步落库 + engine 调用 + 报告持久化）、查询、取消、重跑、删除、
 * 报告读取、多任务对比、基准候选、常量代理。
 */
public interface BacktestService {

    /**
     * 提交回测任务。同步落 PENDING 任务后异步执行，返回任务 VO（status=PENDING）。
     *
     * @param req         请求体（mode/strategyId/versionNo/overrideConfig/benchmark）
     * @param currentUser 创建人（来自 UserContext，异步线程不可读 ThreadLocal，故显式传入）
     */
    BacktestTaskVO run(BacktestRunRequestDTO req, String currentUser);

    /**
     * 分页查询任务列表。支持 strategyId / status / 起止日期筛选。
     */
    PageResult<BacktestTaskVO> listTasks(int page, int size, String strategyId, String status,
                                          String startDate, String endDate);

    /** 按 taskId（UUID）查询任务。 */
    BacktestTaskVO getTask(String taskId);

    /** 按主键 id（数字）查询任务。 */
    BacktestTaskVO getTaskById(Long backtestId);

    /** 取消任务（仅 PENDING 可取消）。 */
    BacktestTaskVO cancelTask(String taskId);

    /** 重跑任务（复用原 overrideConfig + benchmark，不删原任务，生成新任务）。 */
    BacktestTaskVO rerunTask(String taskId, String currentUser);

    /** 物理删除回测任务及其报告（按主键 id）。 */
    void deleteBacktest(Long backtestId);

    /** 读取回测报告（按主键 id）。 */
    BacktestReportVO getReport(Long backtestId);

    /** 多任务对比（按主键 id 列表）。 */
    BacktestCompareVO compare(List<Long> ids);

    /** 基准候选白名单。 */
    List<Map<String, String>> listBenchmarks();

    /** 常量代理（调 engine /constants）。 */
    Object getConstants();

    /**
     * 为参数寻优（spec 015 FR-O1）装配上下文：加载策略版本 configJson + 复用 buildKlineData
     * 拼 K 线数据。OptimizeController 拿到后透传给 engine {@code /optimize}。
     * <p>
     * 与 run() 共用 configJson 加载 + 范式校验 + K 线装配逻辑，但不落 backtest 任务表、
     * 不异步执行（engine 侧寻优任务自带异步状态机）。
     *
     * @param strategyId 策略 ID
     * @param versionNo  版本号（null 取 currentVersion）
     * @return {@code {"config": <configJson>, "kline_data": <JSONObject>}}
     */
    JSONObject buildOptimizeContext(String strategyId, Integer versionNo);
}
