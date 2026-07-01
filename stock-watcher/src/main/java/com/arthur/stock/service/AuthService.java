package com.arthur.stock.service;

import com.arthur.stock.model.UserDO;

/**
 * 认证服务接口，处理用户登录和TOTP两步验证
 */
public interface AuthService {

    /**
     * 用户名密码认证
     *
     * @param username   用户名
     * @param rawPassword 原始密码
     * @return 认证成功的用户对象（密码已脱敏），失败返回null
     */
    UserDO authenticate(String username, String rawPassword);

    /**
     * 检查用户是否已完成TOTP两步验证设置
     *
     * @param user 用户对象
     * @return 已设置TOTP密钥返回true
     */
    boolean isTotpSetupComplete(UserDO user);

    /**
     * 验证TOTP验证码
     *
     * @param secret TOTP密钥
     * @param code   用户输入的验证码
     * @return 验证码正确返回true
     */
    boolean verifyTotp(String secret, int code);

    /**
     * 为用户生成新的TOTP密钥并保存
     *
     * @param userId 用户ID
     * @return 新生成的TOTP密钥
     */
    String generateTotpSecret(Long userId);

    /**
     * 初始化默认管理员账号，仅在系统中无任何用户时创建
     */
    void initDefaultAdmin();

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户对象（密码已脱敏），不存在时返回null
     */
    UserDO findByUsername(String username);
}
