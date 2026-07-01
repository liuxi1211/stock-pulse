package com.arthur.stock.exception;

import lombok.Getter;

/**
 * 业务错误码枚举，定义各类业务异常的错误码和描述信息
 */
@Getter
public enum ErrorCode {

    /** 请求参数错误 */
    BAD_REQUEST(400, "请求参数错误"),
    /** 未登录 */
    UNAUTHORIZED(401, "未登录"),
    /** 无权限 */
    FORBIDDEN(403, "无权限"),
    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),
    /** 数据冲突 */
    CONFLICT(409, "数据冲突"),

    /** 用户名已存在 */
    USER_EXISTS(1001, "用户名已存在"),
    /** 用户不存在 */
    USER_NOT_FOUND(1002, "用户不存在"),
    /** 不能删除自己 */
    SELF_DELETE(1003, "不能删除自己"),
    /** 股票不存在 */
    STOCK_NOT_FOUND(1004, "股票不存在"),
    /** 已在自选股中 */
    WATCHLIST_EXISTS(1005, "已在自选股中"),
    /** 无效的角色值 */
    INVALID_ROLE(1006, "无效的角色值"),
    /** Python 计算服务不可达 */
    PYTHON_SERVICE_UNAVAILABLE(2001, "计算服务不可用"),
    /** 因子定义错误 */
    FACTOR_DEFINITION_ERROR(2002, "因子定义错误");

    /** 错误码 */
    private final int code;
    /** 错误描述 */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
