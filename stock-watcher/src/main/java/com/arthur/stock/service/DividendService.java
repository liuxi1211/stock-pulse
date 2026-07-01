package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.DividendDTO;

import java.util.List;

/**
 * 分红送股服务
 */
public interface DividendService {

    /**
     * 按股票代码查询分红送股数据
     *
     * @param tsCode 股票代码，如 600848.SH
     */
    List<DividendDTO> queryByTsCode(String tsCode);

    /**
     * 拉取某只股票的分红送股数据并保存到数据库。
     * 首次全量拉取，后续只拉取增量部分（按公告日判断）。
     *
     * @param tsCode 股票代码，如 600848.SH
     */
    List<DividendDTO> fetchAndSaveDividend(String tsCode);

    /**
     * 按公告日拉取全市场分红送股数据并保存到数据库
     *
     * @param annDate 公告日，格式 yyyyMMdd
     */
    List<DividendDTO> fetchAndSaveByAnnDate(String annDate);
}
