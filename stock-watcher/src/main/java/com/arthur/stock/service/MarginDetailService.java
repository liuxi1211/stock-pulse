package com.arthur.stock.service;

/**
 * 融资融券明细数据服务。
 */
public interface MarginDetailService {

    /**
     * 获取最新交易日。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String getLatestTradeDate();
}
