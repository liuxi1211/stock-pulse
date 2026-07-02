package com.arthur.stock.service;

import com.arthur.stock.dto.factor.FactorBatchComputeRequestDTO;
import com.arthur.stock.dto.factor.FactorComputeRequestDTO;
import com.arthur.stock.dto.factor.FactorCreateRequestDTO;
import com.arthur.stock.dto.factor.FactorUpdateRequestDTO;
import com.arthur.stock.vo.FactorCategoryVO;
import com.arthur.stock.vo.FactorVO;

import java.util.List;
import java.util.Map;

/**
 * 因子服务：缓存 engine 因子元数据（Caffeine），写操作主动失效缓存。
 * 计算接口由 {@code FactorClient} 完成协议解析与错误兜底，返回细分 Map。
 */
public interface FactorService {

    /** 因子列表（可按 category / source 过滤），缓存 factorList */
    List<FactorVO> listFactors(String category, String source);

    /** 单因子详情，缓存 factorDetail */
    FactorVO getFactor(String factorKey);

    /** 分类列表，缓存 factorCategories */
    List<FactorCategoryVO> listCategories();

    /** 新增因子（成功后失效 factorList / factorDetail / factorCategories） */
    FactorVO createFactor(FactorCreateRequestDTO request);

    /** 修改因子（成功后失效缓存） */
    FactorVO updateFactor(String factorKey, FactorUpdateRequestDTO request);

    /** 删除因子（成功后失效缓存） */
    void deleteFactor(String factorKey);

    /** 单标的多因子计算，返回 {@code factorKey -> 结果序列}（多输出因子为嵌套 Map） */
    Map<String, Object> compute(FactorComputeRequestDTO request);

    /** 多标的批量计算，返回 {@code symbol -> (factorKey -> 结果序列)} */
    Map<String, Map<String, Object>> batchCompute(FactorBatchComputeRequestDTO request);
}
