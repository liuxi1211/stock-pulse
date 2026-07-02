package com.arthur.stock.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "重置用户 TOTP 密钥响应")
public class UserTotpResetResponseDTO {

    @Schema(description = "新的 TOTP 密钥明文")
    private String secret;

    @Schema(description = "TOTP 二维码 otpAuth 链接")
    private String otpAuthUrl;
}
