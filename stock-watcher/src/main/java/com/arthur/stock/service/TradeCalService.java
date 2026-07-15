package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.TradeCalDTO;
import com.arthur.stock.model.TradeCalDO;

import java.util.List;
import java.util.Map;

/**
 * 交易日历服务
 */
public interface TradeCalService {

    /**
     * 从 Tushare 拉取交易日历并保存到数据库
     *
     * @param exchange  交易所（可选）
     * @param startDate 开始日期（可选）
     * @param endDate   结束日期（可选）
     * @return 拉取到的交易日历列表
     */
    List<TradeCalDTO> fetchAndSaveTradeCal(String exchange, String startDate, String endDate);

    /**
     * 查询本地数据库中的交易日历
     *
     * @param exchange  交易所（可选）
     * @param startDate 开始日期（可选）
     * @param endDate   结束日期（可选）
     * @param isOpen    是否交易（可选）
     */
    List<TradeCalDTO> queryLocal(String exchange, String startDate, String endDate, String isOpen);

    /**
     * 批量查询指定交易所、指定日期范围内（仅交易日 is_open=1）的调仓标记。
     * <p>
     * 供 BacktestServiceImpl.buildKlineData 在循环外一次性查询，
     * 再按每根 bar 的 trade_date 取标记注入，避免逐 bar 查表。
     *
     * @param exchange  交易所代码（SSE/SZSE），必填
     * @param startDate 开始日期 yyyyMMdd（含，可选，null 表示不限）
     * @param endDate   结束日期 yyyyMMdd（含，可选，null 表示不限）
     * @return key=cal_date(yyyyMMdd)，value=含 6 个标记字段的 TradeCalDO
     */
    Map<String, TradeCalDO> queryFlagsByRange(String exchange, String startDate, String endDate);
}
