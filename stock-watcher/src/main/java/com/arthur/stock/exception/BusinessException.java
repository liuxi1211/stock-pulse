package com.arthur.stock.exception;

import lombok.Getter;

/**
 * 业务异常类，携带错误码和错误信息，由GlobalExceptionHandler统一捕获处理
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 错误码 */
    private final int code;

    /**
     * 使用预定义ErrorCode构造业务异常
     *
     * @param errorCode 错误码枚举
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 使用预定义ErrorCode和自定义消息构造业务异常
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /**
     * 使用自定义错误码和消息构造业务异常
     *
     * @param code    自定义错误码
     * @param message 自定义错误消息
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
