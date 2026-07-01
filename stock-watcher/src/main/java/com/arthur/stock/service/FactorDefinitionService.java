package com.arthur.stock.service;

import com.alibaba.fastjson2.JSON;
import com.arthur.stock.dto.FactorReferenceDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.vo.factor.FactorComputeParamVO;
import com.arthur.stock.vo.factor.FactorDefVO;
import com.arthur.stock.vo.factor.FactorRegistryVO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FactorDefinitionService {

    private static final Set<String> VALID_INPUTS = Set.of("open", "high", "low", "close", "volume");

    private final Map<String, FactorDefVO> store = new TreeMap<>(Comparator.naturalOrder());

    private volatile FactorRegistryVO cachedRegistry;

    private final ApplicationContext applicationContext;

    public FactorDefinitionService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        loadFromClasspath();
    }

    public FactorRegistryVO reload() {
        store.clear();
        loadFromClasspath();
        cachedRegistry = buildRegistry();
        return cachedRegistry;
    }

    public FactorRegistryVO getRegistry() {
        FactorRegistryVO r = cachedRegistry;
        if (r == null) {
            r = buildRegistry();
            cachedRegistry = r;
        }
        return r;
    }

    public FactorDefVO get(String factorKey) {
        FactorDefVO def = store.get(factorKey);
        if (def == null) {
            throw new BusinessException(ErrorCode.FACTOR_DEFINITION_ERROR, "未找到因子: " + factorKey);
        }
        return def;
    }

    public void validate(List<FactorComputeParamVO> factors) {
        if (factors == null || factors.isEmpty()) {
            throw new BusinessException(ErrorCode.FACTOR_DEFINITION_ERROR, "factors 不能为空");
        }
        for (FactorComputeParamVO f : factors) {
            get(f.getFactorKey());
        }
    }

    public void validateRefs(List<FactorReferenceDTO> factors) {
        if (factors == null || factors.isEmpty()) {
            throw new BusinessException(ErrorCode.FACTOR_DEFINITION_ERROR, "factors 不能为空");
        }
        for (FactorReferenceDTO f : factors) {
            get(f.getFactor());
        }
    }

    private void loadFromClasspath() {
        try {
            Resource[] resources = applicationContext.getResources("classpath*:factor-definitions/*.json");
            if (resources == null || resources.length == 0) {
                log.warn("未在 classpath 中找到 factor-definitions/*.json 因子定义文件");
                return;
            }
            for (Resource res : resources) {
                String fileName = res.getFilename();
                try (InputStream is = res.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                     StringWriter sw = new StringWriter()) {
                    int c;
                    while ((c = br.read()) != -1) {
                        sw.write(c);
                    }
                    FactorDefVO def = JSON.parseObject(sw.toString(), FactorDefVO.class);
                    validateDef(fileName, def);
                    store.put(def.getFactorKey(), def);
                }
            }
            log.info("Loaded {} factor definitions", store.size());
        } catch (BusinessException be) {
            throw be;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.FACTOR_DEFINITION_ERROR,
                    "读取因子定义文件失败: " + ex.getMessage());
        }
    }

    private void validateDef(String fileName, FactorDefVO def) {
        if (def == null) {
            throw new BusinessException(ErrorCode.FACTOR_DEFINITION_ERROR, fileName + ": 文件解析为空");
        }
        if (def.getFactorKey() == null || def.getFactorKey().isBlank()) {
            throw new BusinessException(ErrorCode.FACTOR_DEFINITION_ERROR, fileName + ": factorKey 不能为空");
        }
        List<String> inputs = def.getInputs() == null ? Collections.emptyList() : def.getInputs();
        for (String in : inputs) {
            if (!VALID_INPUTS.contains(in)) {
                throw new BusinessException(ErrorCode.FACTOR_DEFINITION_ERROR,
                        fileName + ": inputs 必须是 [open/high/low/close/volume] 的子集, 非法值: " + in);
            }
        }
    }

    private FactorRegistryVO buildRegistry() {
        List<FactorDefVO> list = new ArrayList<>(store.values());
        list.sort(Comparator.comparing(FactorDefVO::getFactorKey));
        List<String> categories = list.stream()
                .map(FactorDefVO::getCategory)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        return FactorRegistryVO.builder()
                .factors(Collections.unmodifiableList(list))
                .count(list.size())
                .categories(Collections.unmodifiableList(categories))
                .build();
    }
}
