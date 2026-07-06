package com.arthur.stock.service.impl;

import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.mapper.UserMapper;
import com.arthur.stock.model.Role;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.UserService;
import com.arthur.stock.util.TotpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 创建新用户，密码自动加密，自动生成TOTP密钥
     */
    @Override
    public UserDO createUser(String username, String rawPassword, String email, String phone, Role role) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, username));
        if (count > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }

        String secret = TotpUtil.generateSecret();

        UserDO user = new UserDO();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setEmail(email);
        user.setPhone(phone);
        user.setRole(role != null ? role : Role.USER);
        user.setEnabled(true);
        user.setTotpSecret(secret);
        userMapper.insert(user);

        user.setPassword(null);
        return user;
    }

    /**
     * 分页查询用户，支持按关键字在用户名、邮箱、手机号中模糊匹配
     */
    @Override
    public Page<UserDO> listUsers(String keyword, int page, int size) {
        Page<UserDO> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<UserDO> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w
                    .like(UserDO::getUsername, keyword)
                    .or().like(UserDO::getEmail, keyword)
                    .or().like(UserDO::getPhone, keyword));
        }
        wrapper.orderByDesc(UserDO::getCreatedAt);

        Page<UserDO> result = userMapper.selectPage(pageParam, wrapper);
        result.getRecords().forEach(u -> u.setPassword(null));
        return result;
    }

    /**
     * 删除指定用户
     */
    @Override
    public boolean deleteUser(Long userId) {
        return userMapper.deleteById(userId) > 0;
    }

    /**
     * 重置用户TOTP密钥，生成新密钥并更新到数据库
     */
    @Override
    public String resetTotp(Long userId) {
        String newSecret = TotpUtil.generateSecret();
        userMapper.update(null,
                new LambdaUpdateWrapper<UserDO>()
                        .eq(UserDO::getId, userId)
                        .set(UserDO::getTotpSecret, newSecret));
        return newSecret;
    }

    /**
     * 根据ID查询用户，返回结果中密码已脱敏
     */
    @Override
    public UserDO findById(Long userId) {
        UserDO user = userMapper.selectById(userId);
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }
}