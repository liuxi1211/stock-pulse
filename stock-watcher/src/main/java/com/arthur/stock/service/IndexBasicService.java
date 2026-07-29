package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.IndexBasicDTO;
import com.arthur.stock.service.DataCheckable;

import java.util.List;

/**
 * 指数基本信息服务。
 * <p>
 * 负责从 tushare index_basic 接口（doc_id=94）拉取全部市场的指数基础信息并落库，
 * 作为 index_daily / index_weight 等指数行情表的「指数代码主数据源」。
 */
public interface IndexBasicService extends DataCheckable {

    /**
     * 从 Tushare 拉取全部市场的指数基础信息并全量替换落库（清表重建）。
     * <p>
     * index_basic 是低频变更的维度表，采用全量替换策略：deleteAll + 分批 insertBatch。
     *
     * @return 拉取并落库的总记录数
     */
    int fetchAndSaveAll();

    /**
     * 按市场拉取指数基础信息（可选，供按需补数使用）。
     *
     * @param market 市场（SSE/SZSE/CSI/SW/MSCI/CICC/SWHK/OTH）
     * @return 拉取并落库的记录数
     */
    int fetchAndSaveByMarket(String market);

    /**
     * 从本地库查询指数基础信息，支持按市场/类别/关键字筛选。
     */
    List<IndexBasicDTO> queryLocal(String market, String category, String keyword);
}
