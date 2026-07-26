package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.AdjFactorDTO;
import com.arthur.stock.model.AdjFactorDO;

import java.util.List;

/**
 * 复权因子服务
 */
public interface AdjFactorService {

    /**
     * 按股票代码和日期范围查询复权因子
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 开始日期，格式 yyyyMMdd
     * @param endDate   结束日期，格式 yyyyMMdd
     */
    List<AdjFactorDTO> queryByCodeAndDateRange(String tsCode, String startDate, String endDate);

    /**
     * 按交易日期查询全市场复权因子
     *
     * @param tradeDate 交易日期，格式 yyyyMMdd
     */
    List<AdjFactorDTO> queryByTradeDate(String tradeDate);

    /**
     * 拉取某只股票的复权因子并保存到数据库。
     * 首次拉取最近30年数据，后续只拉取增量部分。
     * 内部按 offset/limit 分页拉取（每页 5000 条），查一页存一页，避免内存溢出。
     *
     * @param tsCode 股票代码，如 000001.SZ
     * @return 实际保存的记录数
     */
    int fetchAndSaveAdjFactor(String tsCode);

    /**
     * 拉取某只股票的复权因子并保存到数据库（带已知最新日期，避免 N+1 查询）。
     * 当调用方已持有全量最新日期映射时使用，省去每只股票的单独查询。
     * 内部按 offset/limit 分页拉取（每页 5000 条），查一页存一页，避免内存溢出。
     *
     * @param tsCode          股票代码
     * @param knownLastDate   已知的该股票最新交易日期（yyyyMMdd），可为 null（表示无数据）
     * @return 实际保存的记录数
     */
    int fetchAndSaveAdjFactor(String tsCode, String knownLastDate);

    /**
     * 按交易日期拉取全市场复权因子并保存到数据库。
     * 内部按 offset/limit 分页拉取（每页 5000 条），查一页存一页，避免内存溢出。
     *
     * @param tradeDate 交易日期，格式 yyyyMMdd
     * @return 实际保存的记录数
     */
    int fetchAndSaveByTradeDate(String tradeDate);

    /**
     * 按日期范围拉取全市场复权因子并保存到数据库。
     * 双层分页：外层按 10 个交易日为一个窗口分段，内层每个窗口内 offset/limit 分页拉取，
     * 查一页存一页，避免一次性加载大量数据导致内存溢出。
     *
     * @param startDate 开始日期，格式 yyyyMMdd（含）
     * @param endDate   结束日期，格式 yyyyMMdd（含）
     * @return 实际保存的总记录数
     */
    int fetchAndSaveByDateRange(String startDate, String endDate);

    /**
     * 从本地数据库查询指定股票的全部复权因子（按日期升序）
     *
     * @param tsCode 股票代码，如 000001.SZ
     */
    List<AdjFactorDO> queryLocalByTsCode(String tsCode);
}
