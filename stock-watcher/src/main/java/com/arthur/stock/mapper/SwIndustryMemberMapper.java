package com.arthur.stock.mapper;

import com.arthur.stock.model.SwIndustryMemberDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 申万行业成分股数据访问层，基于 MyBatis-Plus BaseMapper 提供对 sw_industry_member 表的 CRUD 操作。
 * <p>
 * 关键查询：按 point-in-time 取个股的一级行业归属（用于回测防幸存者偏���）。
 */
@Mapper
public interface SwIndustryMemberMapper extends BaseMapper<SwIndustryMemberDO> {

    /**
     * 批量插入行业成分股记录。
     */
    int insertBatch(@Param("list") List<SwIndustryMemberDO> list);

    /**
     * 按 (ts_code, index_code, update_date) 批量删除。
     */
    int deleteBatchByKeys(@Param("list") List<SwIndustryMemberDO> list);

    /**
     * 取个股当前（is_new=1）所属的一级行业。
     * <p>
     * level=1 判定通过 index_code IN (SELECT index_code FROM sw_industry WHERE level=1) 关联。
     *
     * @param tsCode 股票代码，如 000001.SZ
     * @return 当前一级行业成分股记录（含 index_code / index_name），无则 null
     */
    @Select("SELECT ts_code, index_code, index_name, in_date, out_date, is_new, src, update_date "
            + "FROM sw_industry_member "
            + "WHERE ts_code = #{tsCode} AND is_new = 1 "
            + "AND index_code IN (SELECT index_code FROM sw_industry WHERE level = 1) "
            + "LIMIT 1")
    SwIndustryMemberDO selectLatestL1ByTsCode(@Param("tsCode") String tsCode);

    /**
     * point-in-time 查询：取个股 ≤ 指定日期最新生效的一级行业归属。
     * <p>
     * update_date <= trade_date，按 update_date 倒序取首条。用于回测在每个调仓日做行业归属判定，
     * 避免用未来才变更的行业归属做决策（lookahead bias）。
     *
     * @param tsCode    股票代码
     * @param tradeDate 交易日 yyyyMMdd
     * @return ≤ trade_date 最新生效的一级行业成分股记录，无则 null
     */
    @Select("SELECT ts_code, index_code, index_name, in_date, out_date, is_new, src, update_date "
            + "FROM sw_industry_member "
            + "WHERE ts_code = #{tsCode} AND update_date <= #{tradeDate} "
            + "AND index_code IN (SELECT index_code FROM sw_industry WHERE level = 1) "
            + "ORDER BY update_date DESC LIMIT 1")
    SwIndustryMemberDO selectL1AtDate(@Param("tsCode") String tsCode, @Param("tradeDate") String tradeDate);

    /**
     * 批量取多只股票当前所属的一级行业（实时回测/选股用，避免逐只查询）。
     *
     * @param tsCodes 股票代码列表
     * @return 成分股记录列表（每只最多一条 is_new=1 的一级行业记录）
     */
    @Select("<script>"
            + "SELECT ts_code, index_code, index_name, in_date, out_date, is_new, src, update_date "
            + "FROM sw_industry_member "
            + "WHERE is_new = 1 "
            + "AND index_code IN (SELECT index_code FROM sw_industry WHERE level = 1) "
            + "AND ts_code IN "
            + "<foreach item='c' collection='tsCodes' open='(' separator=',' close=')'>#{c}</foreach>"
            + "</script>")
    List<SwIndustryMemberDO> selectLatestL1ByTsCodes(@Param("tsCodes") List<String> tsCodes);

    /**
     * 区间批量：取多只股票的全部历史一级行业成分股记录（is_new 1/0 都要，含已剔除的）。
     * <p>
     * 用于 point-in-time 批量查询：调用方按 ts_code 分组 + update_date forward-fill
     * 构造每个 trade_date 的当日归属。
     *
     * @param tsCodes 股票代码列表
     * @return 全部历史一级成分股记录（按 ts_code, update_date 升序）
     */
    @Select("<script>"
            + "SELECT ts_code, index_code, index_name, in_date, out_date, is_new, src, update_date "
            + "FROM sw_industry_member "
            + "WHERE index_code IN (SELECT index_code FROM sw_industry WHERE level = 1) "
            + "AND ts_code IN "
            + "<foreach item='c' collection='tsCodes' open='(' separator=',' close=')'>#{c}</foreach>"
            + " ORDER BY ts_code, update_date ASC"
            + "</script>")
    List<SwIndustryMemberDO> selectAllL1HistoryByTsCodes(@Param("tsCodes") List<String> tsCodes);

    /**
     * 查询全量当前一级成分股（is_new='1' 且属于指定 src 的 level=1 行业）。
     */
    @Select("SELECT ts_code, index_code, index_name, in_date, out_date, is_new, src, update_date "
            + "FROM sw_industry_member "
            + "WHERE is_new = 1 AND src = #{src} "
            + "AND index_code IN (SELECT index_code FROM sw_industry WHERE level = 1 AND src = #{src})")
    List<SwIndustryMemberDO> selectAllCurrentL1Members(@Param("src") String src);

    /**
     * 按行业代码查询当前成分股（is_new='1'）。
     */
    @Select("SELECT ts_code, index_code, index_name, in_date, out_date, is_new, src, update_date "
            + "FROM sw_industry_member "
            + "WHERE index_code = #{indexCode} AND is_new = 1 AND src = #{src}")
    List<SwIndustryMemberDO> selectMembersByIndexCode(@Param("indexCode") String indexCode, @Param("src") String src);

    // ==================== 数据管控检查 ====================

    /**
     * 统计有行业分类的股票数（distinct ts_code，is_new='1'）。
     */
    int countCoveredStocks();

    /**
     * 统计 out_date IS NOT NULL AND in_date > out_date 的记录数。
     */
    int countDateLogicErrors();

    /**
     * 板块资金流聚合：stock_moneyflow JOIN sw_industry_member(is_new=1)，按 index_code 分组。
     * <p>
     * 只关联一级行业（index_code IN sw_industry.level=1），返回 28 行以内的聚合结果，
     * 各净额单位「万元」。主力净额 = 大单净额 + 特大单净额。
     * <p>
     * 仅按 index_code 分组，index_name 用 MAX() 兜底，避免历史成分股记录中
     * 同一 index_code 出现多个 index_name 时被拆成多行。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 每行 Map 含 index_code / index_name / main_net / elg_net / lg_net / md_net / sm_net / net_mf
     */
    @Select("SELECT m.index_code AS index_code, MAX(m.index_name) AS index_name, "
            + " SUM((IFNULL(mf.buy_lg_amount,0) - IFNULL(mf.sell_lg_amount,0)) "
            + "   + (IFNULL(mf.buy_elg_amount,0) - IFNULL(mf.sell_elg_amount,0))) AS main_net, "
            + " SUM(IFNULL(mf.buy_elg_amount,0) - IFNULL(mf.sell_elg_amount,0)) AS elg_net, "
            + " SUM(IFNULL(mf.buy_lg_amount,0) - IFNULL(mf.sell_lg_amount,0)) AS lg_net, "
            + " SUM(IFNULL(mf.buy_md_amount,0) - IFNULL(mf.sell_md_amount,0)) AS md_net, "
            + " SUM(IFNULL(mf.buy_sm_amount,0) - IFNULL(mf.sell_sm_amount,0)) AS sm_net, "
            + " SUM(IFNULL(mf.net_mf_amount,0)) AS net_mf "
            + "FROM sw_industry_member m "
            + "JOIN stock_moneyflow mf ON mf.ts_code = m.ts_code AND mf.trade_date = #{tradeDate} "
            + "WHERE m.is_new = 1 "
            + "AND m.index_code IN (SELECT index_code FROM sw_industry WHERE level = 1) "
            + "GROUP BY m.index_code")
    List<Map<String, Object>> selectMoneyflowGroupByIndustry(@Param("tradeDate") String tradeDate);

    /**
     * 板块估值聚合：daily_basic JOIN sw_industry_member(is_new=1)，按 index_code 分组。
     * <p>
     * peTtm 为市值加权（仅统计 pe_ttm>0 且 total_mv>0 的成分股）；
     * pb 为算术平均（仅统计 pb>0 的成分股）。
     * <p>
     * 仅按 index_code 分组，index_name 用 MAX() 兜底（同 selectMoneyflowGroupByIndustry）。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 每行 Map 含 index_code / index_name / pe_ttm / pb
     */
    @Select("SELECT m.index_code AS index_code, MAX(m.index_name) AS index_name, "
            + " SUM(CASE WHEN db.pe_ttm > 0 AND db.total_mv > 0 THEN db.pe_ttm * db.total_mv ELSE 0 END) "
            + "   / NULLIF(SUM(CASE WHEN db.pe_ttm > 0 AND db.total_mv > 0 THEN db.total_mv ELSE 0 END), 0) AS pe_ttm, "
            + " AVG(CASE WHEN db.pb > 0 THEN db.pb ELSE NULL END) AS pb "
            + "FROM sw_industry_member m "
            + "JOIN daily_basic db ON db.ts_code = m.ts_code AND db.trade_date = #{tradeDate} "
            + "WHERE m.is_new = 1 "
            + "AND m.index_code IN (SELECT index_code FROM sw_industry WHERE level = 1) "
            + "GROUP BY m.index_code")
    List<Map<String, Object>> selectValuationGroupByIndustry(@Param("tradeDate") String tradeDate);
}
