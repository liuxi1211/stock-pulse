package com.arthur.stock.service;

import java.util.List;
import java.util.Map;

/**
 * 申万行业分类服务。
 * <p>
 * 负责从 tushare index_classify / index_member_all 接口拉取申万行业（SWS2021）分类与成分股
 * 并落库，提供实时选股（最新一级行业）与回测（point-in-time 一级行业）两类查询。
 */
public interface SwIndustryService {

    /**
     * 拉取申万行业分类（index_classify）并落库（幂等：按 src 先删后插）。
     *
     * @param src 分类版本，如 SWS2021
     * @return 落库记录数
     */
    int fetchAndSaveClassify(String src);

    /**
     * 按行业代码拉取该行业的成分股（index_member_all）并落库。
     *
     * @param indexCode 行业代码
     * @param src       分类版本
     * @return 落库记录数
     */
    int fetchAndSaveMembers(String indexCode, String src);

    /**
     * 全量分页拉取申万行业成分股（index_member_all，按 ts_code 无参遍历，每页 2000 条）。
     *
     * @param src 分类版本
     * @return 落库记录数
     */
    int fetchAndSaveAllMembers(String src);

    /**
     * 取个股当前（is_new=1）所属的一级行业代码，无则 null。
     */
    String getLatestL1Industry(String tsCode);

    /**
     * 批量取多只股票当前所属的一级行业代码（key=tsCode，value=index_code），
     * 未匹配的 tsCode 不在 Map 中。
     */
    Map<String, String> getLatestL1Industries(List<String> tsCodes);

    /**
     * point-in-time 查询：取个股 ≤ 指定日期最新生效的一级行业代码，无则 null。
     */
    String getL1IndustryAt(String tsCode, String tradeDate);
}
