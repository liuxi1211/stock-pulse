package com.arthur.stock.util;

import org.springframework.beans.BeanUtils;

public final class DtoConverter {

    private DtoConverter() {
    }

    public static <T> T convert(Object source, Class<T> targetClass) {
        try {
            T target = targetClass.getDeclaredConstructor().newInstance();
            BeanUtils.copyProperties(source, target);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert to " + targetClass.getName(), e);
        }
    }
}
