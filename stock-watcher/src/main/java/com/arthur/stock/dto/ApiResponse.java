package com.arthur.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "统一API响应封装")
public class ApiResponse<T> {

    @Schema(description = "响应状态码，200表示成功")
    private int code;

    @Schema(description = "响应消息")
    private String message;

    @Schema(description = "响应数据")
    private T data;

    @Schema(description = "错误明细列表（仅校验类错误返回，如策略配置校验失败）")
    private List<?> errors;

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(500, message, null);
    }

    /**
     * 构造带 errors 明细的失败响应（用于校验类错误，如策略配置校验失败）。
     *
     * @param code    错误码
     * @param message 错误消息
     * @param errors  错误明细列表
     */
    public static <T> ApiResponse<T> errorWithErrors(int code, String message, List<?> errors) {
        ApiResponse<T> r = new ApiResponse<>(code, message, null);
        r.setErrors(errors);
        return r;
    }

    /**
     * 构造带 errors 明细和 data（如乐观锁冲突时携带 currentVersion）的失败响应。
     */
    public static <T> ApiResponse<T> errorWithData(int code, String message, T data, List<?> errors) {
        ApiResponse<T> r = new ApiResponse<>(code, message, data);
        r.setErrors(errors);
        return r;
    }
}
