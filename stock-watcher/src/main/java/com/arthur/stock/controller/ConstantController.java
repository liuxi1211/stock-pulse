package com.arthur.stock.controller;

import com.arthur.stock.constant.DisplayableEnum;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.EnumOptionDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "常量枚举", description = "系统常量枚举数据，用于前端下拉框等选择组件")
@Slf4j
@RestController
@RequestMapping("/constants")
public class ConstantController {

    private static final List<String> SCAN_PACKAGES = List.of(
            "com.arthur.stock.constant",
            "com.arthur.stock.model"
    );

    private Map<String, List<EnumOptionDTO>> cache;

    @PostConstruct
    public void init() {
        cache = new LinkedHashMap<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isIndependent()
                                && beanDefinition.getMetadata().isConcrete();
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(DisplayableEnum.class));

        for (String basePackage : SCAN_PACKAGES) {
            for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    if (clazz.isEnum()) {
                        registerEnum(clazz);
                    }
                } catch (ClassNotFoundException ignored) {}
            }
        }

        log.info("Loaded {} enum constants: {}", cache.size(), cache.keySet());
    }

    @Operation(summary = "获取所有枚举常量", description = "返回系统中所有实现了DisplayableEnum接口的枚举类，用于前端下拉选择框。返回 Map<枚举类名, 选项列表> 属于「纯键值缓存返回」例外（见 api-design §11.3-2）")
    @GetMapping
    public ApiResponse<Map<String, List<EnumOptionDTO>>> getAllConstants() {
        return ApiResponse.success(cache);
    }

    private void registerEnum(Class<?> clazz) {
        Object[] constants = clazz.getEnumConstants();
        if (constants == null || constants.length == 0) return;
        List<EnumOptionDTO> options = Arrays.stream(constants)
                .map(e -> (DisplayableEnum) e)
                .map(e -> new EnumOptionDTO(e.getCode(), e.getLabel()))
                .collect(Collectors.toList());
        cache.put(clazz.getSimpleName(), options);
    }
}
