package com.arthur.stock.service;

import com.arthur.stock.dto.PageResult;
import com.arthur.stock.vo.StockVO;
import com.arthur.stock.vo.SuggestItemVO;

import java.util.List;

/**
 * 股票搜索服务接口
 */
public interface SearchService {

    /**
     * 搜索股票，支持按代码或名称模糊匹配
     */
    PageResult<StockVO> searchStocks(String keyword, int page, int size);

    /**
     * 搜索建议，返回匹配关键字的前10条股票代码和名称
     */
    List<SuggestItemVO> suggestStocks(String keyword);

    /**
     * 批量按 tsCode 查询股票详情（用于已保存方案的 stocks 回填名称）。
     *
     * @param tsCodes tsCode 列表（如 000001.SZ、600000.SH）
     * @return 匹配到的 SuggestItemVO 列表（含 tsCode + code + name）
     */
    List<SuggestItemVO> batchByTsCodes(List<String> tsCodes);
}