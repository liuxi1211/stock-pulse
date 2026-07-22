package com.arthur.stock.mapper;

import com.arthur.stock.model.SwIndustryMemberDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
            + "WHERE ts_code = #{tsCode} AND is_new = '1' "
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
            + "WHERE is_new = '1' "
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
            + "WHERE is_new = '1' AND src = #{src} "
            + "AND index_code IN (SELECT index_code FROM sw_industry WHERE level = 1 AND src = #{src})")
    List<SwIndustryMemberDO> selectAllCurrentL1Members(@Param("src") String src);

    /**
     * 按行业代码查询当前成分股（is_new='1'）。
     */
    @Select("SELECT ts_code, index_code, index_name, in_date, out_date, is_new, src, update_date "
            + "FROM sw_industry_member "
            + "WHERE index_code = #{indexCode} AND is_new = '1' AND src = #{src}")
    List<SwIndustryMemberDO> selectMembersByIndexCode(@Param("indexCode") String indexCode, @Param("src") String src);
}
