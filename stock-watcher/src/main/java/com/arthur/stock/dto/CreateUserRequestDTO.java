package com.arthur.stock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建用户请求参数
 */
@Data
public class CreateUserRequestDTO {

    /** 用户名，长度2-20个字符 */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 20, message = "用户名长度2-20个字符")
    private String username;

    /** 密码，长度6-50个字符 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 50, message = "密码长度6-50个字符")
    private String password;

    /** 邮箱地址 */
    private String email;

    /** 手机号码 */
    private String phone;

    /** 用户角色，如 ADMIN、USER */
    private String role;
}