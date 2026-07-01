package com.arthur.stock.controller;

import com.arthur.stock.constant.DisplayableEnum;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.EnumOptionDTO;
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

/**
 * 常量枚举API控制器，自动扫描项目中实现了DisplayableEnum接口的枚举类，
 * 将其转换为前端下拉框等选择组件可用的选项数据
 */
@Slf4j
@RestController
@RequestMapping("/api/constants")
public class ConstantController {

    private static final List<String> SCAN_PACKAGES = List.of(
            "com.arthur.stock.constant",
            "com.arthur.stock.model"
    );

    private Map<String, List<EnumOptionDTO>> cache;

    /**
     * 初始化时扫描指定包路径下的DisplayableEnum枚举类，缓存选项数据
     */
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

    /**
     * 获取所有枚举常量选项数据
     */
    @GetMapping
    public ApiResponse<Map<String, List<EnumOptionDTO>>> getAllConstants() {
        return ApiResponse.success(cache);
    }

    /**
     * 将枚举类注册到缓存中，提取每个枚举值的code和label
     */
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
