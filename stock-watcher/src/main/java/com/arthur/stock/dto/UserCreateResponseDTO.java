package com.arthur.stock.dto;

import com.arthur.stock.model.UserDO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "创建用户响应")
public class UserCreateResponseDTO {

    @Schema(description = "新建用户信息（已脱敏，不含 totpSecret）")
    private UserDO user;

    @Schema(description = "TOTP 二维码 otpAuth 链接，用于扫码设置")
    private String otpAuthUrl;

    @Schema(description = "TOTP 密钥明文，仅此一次返回，用于手动输入设置")
    private String secret;
}
