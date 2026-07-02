package com.arthur.stock.client;

import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.dto.factor.FactorBatchComputeRequestDTO;
import com.arthur.stock.dto.factor.FactorComputeRequestDTO;
import com.arthur.stock.dto.factor.FactorCreateRequestDTO;
import com.arthur.stock.dto.factor.FactorUpdateRequestDTO;
import com.arthur.stock.vo.FactorCategoryVO;
import com.arthur.stock.vo.FactorVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * stock-engine（Python :8085）的 <b>因子库</b> HTTP 客户端。
 * <p>
 * 仅承担 {@code /python/v1/factors} 命名空间下的接口调用。对外方法 <b>直接返回强类型对象</b>
 * （VO / 细分 Map），由 {@link AbstractEngineClient} 负责拆 engine 响应信封、校验 success、
 * 反序列化与错误兜底（engine 不可达/返回错误体时抛 {@code BusinessException}，不透传原始堆栈）。
 * <ul>
 *   <li>因子元数据 CRUD：{@link #listFactors} / {@link #getFactor} / {@link #listCategories}
 *       / {@link #createFactor} / {@link #updateFactor} / {@link #deleteFactor}</li>
 *   <li>因子计算：{@link #computeFactor} / {@link #batchComputeFactor}（返回细分 Map）</li>
 * </ul>
 * <p>
 * 后续若新增回测、选股等 Python 接口，应另起独立 Client（如 {@code BacktestClient}）
 * 并同样继承 {@link AbstractEngineClient}，不要把多领域接口堆在本类中。
 */
@Component
@RequiredArgsConstructor
public class FactorClient extends AbstractEngineClient {

    private static final String FACTOR_BASE_PATH = "/python/v1/factors";

    private final RestTemplate restTemplate;

    @Value("${python.compute.url}")
    private String engineBaseUrl;

    @Override
    protected String baseUrl() {
        return engineBaseUrl;
    }

    @Override
    protected String basePath() {
        return FACTOR_BASE_PATH;
    }

    @Override
    protected RestTemplate restTemplate() {
        return restTemplate;
    }

    // ==================== 因子元数据 / CRUD ====================

    /**
     * 查询因子列表，支持按分类与来源过滤。
     *
     * @param category 分类，{@code null}/空 表示不限
     * @param source   来源，{@code null}/空 表示不限
     */
    public List<FactorVO> listFactors(String category, String source) {
        StringBuilder suffix = new StringBuilder();
        char sep = '?';
        if (category != null && !category.isBlank()) {
            suffix.append(sep).append("category=").append(category);
            sep = '&';
        }
        if (source != null && !source.isBlank()) {
            suffix.append(sep).append("source=").append(source);
        }
        return getDtoList(url(suffix.toString()), FactorVO.class);
    }

    /**
     * 查询单个因子详情。
     */
    public FactorVO getFactor(String factorKey) {
        return getDto(url("/" + factorKey), FactorVO.class);
    }

    /**
     * 查询因子分类列表。
     */
    public List<FactorCategoryVO> listCategories() {
        return getDtoList(url("/categories"), FactorCategoryVO.class);
    }

    /**
     * 新建因子，返回创建后的最新定义。
     */
    public FactorVO createFactor(FactorCreateRequestDTO body) {
        return exchangeDto(url(""), HttpMethod.POST, body, FactorVO.class);
    }

    /**
     * 更新因子，返回更新后的最新定义。
     */
    public FactorVO updateFactor(String factorKey, FactorUpdateRequestDTO body) {
        return exchangeDto(url("/" + factorKey), HttpMethod.PUT, body, FactorVO.class);
    }

    /**
     * 删除因子。失败会抛 {@code BusinessException}，成功无返回值。
     */
    public void deleteFactor(String factorKey) {
        exchangeData(url("/" + factorKey), HttpMethod.DELETE, null);
    }

    // ==================== 因子计算 ====================

    /**
     * 单标的多因子计算。
     * <p>
     * engine 返回 {@code {factorKey: [值...]}}（长度与输入一致，预热段为 null），
     * 多输出因子的值可能是嵌套 Map（如 MACD 的 {@code {DIF:[...], DEA:[...], "MACD柱":[...]}}）。
     *
     * @return {@code factorKey -> 计算结果序列}（序列元素为 Number 或 null；多输出因子为嵌套 Map）
     */
    public Map<String, Object> computeFactor(FactorComputeRequestDTO body) {
        JSONObject data = exchangeData(url("/compute"), HttpMethod.POST, body);
        return data == null ? Map.of() : data.toJavaObject(Map.class);
    }

    /**
     * 多标的批量因子计算。
     * <p>
     * engine 返回 {@code {symbol: {factorKey: [值...]}}}。
     *
     * @return {@code symbol -> (factorKey -> 计算结果序列)}
     */
    public Map<String, Map<String, Object>> batchComputeFactor(FactorBatchComputeRequestDTO body) {
        JSONObject data = exchangeData(url("/batch-compute"), HttpMethod.POST, body);
        return data == null ? Map.of() : data.toJavaObject(Map.class);
    }
}
