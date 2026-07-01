package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户数据对象，对应 sys_user 表
 */
@Data
@TableName("sys_user")
public class UserDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名 */
    private String username;

    /** 密码（加密存储） */
    private String password;

    /** TOTP认证密钥，用于两步验证 */
    private String totpSecret;

    /** 是否启用 */
    private Boolean enabled;

    /** 邮箱地址 */
    private String email;

    /** 手机号码 */
    private String phone;

    /** 用户角色 */
    private Role role;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}