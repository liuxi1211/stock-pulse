package com.arthur.stock.service;

import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.screener.ScreenPlanCreateRequestDTO;
import com.arthur.stock.dto.screener.ScreenPlanRunRequestDTO;
import com.arthur.stock.dto.screener.ScreenPlanUpdateRequestDTO;
import com.arthur.stock.model.ScreenLockDO;
import com.arthur.stock.vo.ScreenPlanVO;
import com.arthur.stock.vo.ScreenResultVO;
import com.arthur.stock.vo.ScreenLockVO;
import com.arthur.stock.vo.ScreenLockDetailVO;

import java.util.List;

/**
 * 多因子选股中心服务（spec 003 阶段 2 Task 9/10/11，FR-9/FR-10）。
 * <p>
 * 负责：选股方案 CRUD + 执行编排（候选池解析 → candidates 拼装 → HTTP 调 engine → 落库）
 * + 结果锁定与收益追踪（lock/tracking，Task 11 / FR-9）。
 */
public interface ScreenerService {

    /** 新建选股方案 */
    ScreenPlanVO createPlan(ScreenPlanCreateRequestDTO req);

    /** 更新选股方案（仅更新非空字段） */
    ScreenPlanVO updatePlan(Long id, ScreenPlanUpdateRequestDTO req);

    /** 获取方案详情 */
    ScreenPlanVO getPlan(Long id);

    /** 分页查询方案 */
    PageResult<ScreenPlanVO> listPlans(int page, int size);

    /** 删除方案 */
    void deletePlan(Long id);

    /**
     * 执行选股方案（核心编排）：候选池解析 → candidates 拼装 → 调 engine snapshot → 落库。
     */
    ScreenResultVO runPlan(Long id, ScreenPlanRunRequestDTO req);

    /** 获取选股结果详情 */
    ScreenResultVO getResult(Long resultId);

    /** 查询某方案的全部执行结果 */
    List<ScreenResultVO> listResultsByPlan(Long planId);

    // ==================== 结果锁定与收益追踪（spec 003 阶段 2 Task 11，FR-9） ====================

    /**
     * 锁定选股结果为持仓组合快照（生成 screen_lock 记录）。
     *
     * @param resultId 选股结果ID
     * @return 锁定记录 VO
     */
    ScreenLockVO lockResult(Long resultId);

    /**
     * 查询某次锁定的追踪明细（含个股贡献明细）。
     *
     * @param lockId 锁定记录ID
     * @return 锁定明细 VO
     */
    ScreenLockDetailVO getLockDetail(Long lockId);

    /**
     * 列出所有锁定记录（可按方案过滤）。
     *
     * @param planId 方案ID，null 表示全部
     * @return 锁定记录列表（基础字段，不含明细）
     */
    List<ScreenLockVO> listLocks(Long planId);

    /**
     * 计算并更新某条锁定的追踪收益（5/10/20 交易日等权组合收益 + 沪深300基准）。
     * <p>
     * 供 {@code ScreenLockTrackingTask} 调用；事务包裹 updateById。
     *
     * @param lock 待追踪的锁定记录
     */
    void applyTracking(ScreenLockDO lock);

    /**
     * 查询所有状态为 TRACKING 的锁定记录（供定时任务遍历）。
     */
    List<ScreenLockDO> listTrackingLocks();
}
