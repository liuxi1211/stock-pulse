package com.arthur.stock.service;

import java.util.List;
import java.util.Map;

import com.arthur.stock.dto.PageResult;
import com.arthur.stock.vo.IndustryMemberVO;
import com.arthur.stock.vo.IndustryMoneyflowVO;
import com.arthur.stock.vo.IndustryRankingVO;
import com.arthur.stock.vo.IndustryValuationVO;
import com.arthur.stock.vo.SwIndustryVO;

/**
 * 申万行业分类服务。
 * <p>
 * 负责从 tushare index_classify / index_member_all 接口拉取申万行业（SW2021）分类与成分股
 * 并落库，提供实时选股（最新一级行业）与回测（point-in-time 一级行业）两类查询。
 */
public interface SwIndustryService {

    /**
     * 按层级查询行业列表（level=1 返回 28 个申万一级行业）。
     *
     * @param level 行业层级（1/2/3）
     * @return 该层级所有行业的代码与名称
     */
    List<SwIndustryVO> listByLevel(int level);


    /**
     * 拉取申万行业分类（index_classify）并落库（幂等：按 src 先删后插）。
     *
     * @param src 分类版本，如 SW2021
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

    /**
     * 按股票代码反查当前所属申万一级行业（含代码与名称），无则 null。
     * 供个股详情页"行业"标签点击跳转板块行情使用。
     */
    SwIndustryVO getCurrentL1ByTsCode(String tsCode);

    /**
     * 批量 point-in-time：取多只股票在区间 [startDate, endDate] 内每日生效的一级行业。
     * <p>
     * 实现一次性查全部历史一级成分股记录，按 ts_code + update_date forward-fill，
     * 对区间内每个自然日取「≤ 该日最新 update_date」的 index_code。
     *
     * @param tsCodes   股票代码列表
     * @param startDate 区间起始 yyyyMMdd（含），null 时退化为按 update_date 索引
     * @param endDate   区间结束 yyyyMMdd（含）
     * @return key=tsCode, value={trade_date(yyyyMMdd) -> index_code}；
     *         无历史记录或区间内无生效归属的 tsCode 不在内层 map
     */
    Map<String, Map<String, String>> getL1IndustriesPit(List<String> tsCodes, String startDate, String endDate);

    /**
     * 获取行业排行数据（28个申万一级行业）。
     * Java 层多步聚合：行业列表 + 成分股 + 指数行情 + 成分股行情 -> 按行业分组取领涨/领跌。
     */
    List<IndustryRankingVO> getIndustryRanking(String tradeDate);

    /**
     * 计算行业排行数据（绕过缓存，直接执行业务逻辑）。
     * <p>
     * 供需要强制刷新或绕过 Spring AOP 缓存的场景调用。
     */
    List<IndustryRankingVO> computeIndustryRanking(String tradeDate);

    /**
     * 分页查询行业成分股（含最新行情）。
     */
    PageResult<IndustryMemberVO> getIndustryMembers(String industryCode, String tradeDate, int page, int size, String keyword);

    /**
     * 获取板块资金流聚合（申万一级 28 个行业）。
     * <p>
     * 按 sw_industry_member.index_code 分组聚合 stock_moneyflow，返回主力/超大单/大单/中单/小单净额。
     * tradeDate 为 null 时取最新交易日。
     *
     * @param tradeDate 交易日 yyyyMMdd，可空
     * @return 28 个行业的资金流聚合（无数据的行业净额为 null）
     */
    List<IndustryMoneyflowVO> getIndustryMoneyflow(String tradeDate);

    /**
     * 计算板块资金流聚合（绕过缓存，直接执行业务逻辑）。
     */
    List<IndustryMoneyflowVO> computeIndustryMoneyflow(String tradeDate);

    /**
     * 获取板块估值聚合（申万一级 28 个行业）。
     * <p>
     * 按 sw_industry_member.index_code 分组聚合 daily_basic，返回市值加权 PE_TTM、算术平均 PB。
     * tradeDate 为 null 时取最新交易日。
     *
     * @param tradeDate 交易日 yyyyMMdd，可空
     * @return 28 个行业的估值聚合（无数据的行业估值指标为 null）
     */
    List<IndustryValuationVO> getIndustryValuation(String tradeDate);

    /**
     * 计算板块估值聚合（绕过缓存，直接执行业务逻辑）。
     */
    List<IndustryValuationVO> computeIndustryValuation(String tradeDate);
}
