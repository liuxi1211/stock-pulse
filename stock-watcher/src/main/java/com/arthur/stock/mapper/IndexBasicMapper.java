package com.arthur.stock.mapper;

import com.arthur.stock.model.IndexBasicDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 指数基本信息数据访问层，基于 MyBatis-Plus BaseMapper 提供对 index_basic 表的 CRUD 操作。
 */
@Mapper
public interface IndexBasicMapper extends BaseMapper<IndexBasicDO> {

    /**
     * 批量插入指数基本信息（全量重建用，配合 TRUNCATE 或 deleteAll）。
     */
    int insertBatch(@Param("list") List<IndexBasicDO> list);

    /**
     * 清空 index_basic 表（全量替换前的清表，跨方言通用）。
     */
    int deleteAll();

    /**
     * TRUNCATE 清空 index_basic 表（DDL，比 DELETE 快，但会重置自增 ID）。
     * <p>
     * 注意：TRUNCATE 在 MySQL 中为 DDL，会隐式提交且无法回滚，
     * 调用方需自行保证后续 INSERT 的原子性，不可与 INSERT 放在同一事务中。
     */
    int truncateTable();

    /**
     * 返回全部指数代码（ts_code），供 index_daily / index_weight 同步遍历使用。
     */
    List<String> selectAllTsCodes();

    /**
     * 返回 ts_code -> base_date（基期）映射，供 index_daily 全历史拉取确定起始日期。
     */
    List<Map<String, Object>> selectAllBaseDateMap();
}
