package com.arthur.stock.service.impl;

import com.arthur.stock.mapper.UserMapper;
import com.arthur.stock.model.Role;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.AuthService;
import com.arthur.stock.util.TotpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现类，处理用户登录验证和TOTP两步验证
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 用户名密码认证，防止通过响应时间差异判断用户是否存在
     */
    @Override
    public UserDO authenticate(String username, String rawPassword) {
        UserDO user = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, username));
        if (user == null || !user.getEnabled()) {
            passwordEncoder.matches(rawPassword, "$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            return null;
        }
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            return null;
        }
        user.setPassword(null);
        return user;
    }

    /**
     * 检查用户是否已设置TOTP密钥
     */
    @Override
    public boolean isTotpSetupComplete(UserDO user) {
        return user.getTotpSecret() != null && !user.getTotpSecret().isEmpty();
    }

    /**
     * 使用TOTP密钥验证用户输入的验证码
     */
    @Override
    public boolean verifyTotp(String secret, int code) {
        return TotpUtil.verify(secret, code);
    }

    /**
     * 为用户生成新的TOTP密钥并持久化
     */
    @Override
    public String generateTotpSecret(Long userId) {
        String secret = TotpUtil.generateSecret();
        userMapper.update(null,
                new LambdaUpdateWrapper<UserDO>().eq(UserDO::getId, userId).set(UserDO::getTotpSecret, secret));
        return secret;
    }

    /**
     * 初始化默认管理员账号，仅在用户表为空时创建admin/admin123
     */
    @Override
    public void initDefaultAdmin() {
        Long count = userMapper.selectCount(null);
        if (count == 0) {
            UserDO admin = new UserDO();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setEnabled(true);
            admin.setRole(Role.ADMIN);
            userMapper.insert(admin);
            log.info("默认管理员账号已创建: admin / admin123");
        }
    }

    /**
     * 根据用户名查询用户，返回结果中密码已脱敏
     */
    @Override
    public UserDO findByUsername(String username) {
        UserDO user = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, username));
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }
}
