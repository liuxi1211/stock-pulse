package com.arthur.stock.constant;

/**
 * 可展示枚举通用接口，用于前端 code→label 映射
 */
public interface DisplayableEnum {

    /** DB/API 存储值 */
    String getCode();

    /** 中文展示名 */
    String getLabel();
}
