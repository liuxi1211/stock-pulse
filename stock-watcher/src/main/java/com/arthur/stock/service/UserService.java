package com.arthur.stock.service;

import com.arthur.stock.model.Role;
import com.arthur.stock.model.UserDO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 创建新用户
     *
     * @param username   用户名
     * @param rawPassword 原始密码（将自动加密）
     * @param email      邮箱地址
     * @param phone      手机号码
     * @param role       用户角色，为null时默认为USER
     * @return 创建的用户对象（密��已脱敏）
     */
    UserDO createUser(String username, String rawPassword, String email, String phone, Role role);

    /**
     * 分页查询用户列表，支持按关键字模糊搜索
     *
     * @param keyword 搜索关键字（匹配用户名、邮箱、手机）
     * @param page    页码
     * @param size    每页条数
     * @return 分页结果（密码已脱敏）
     */
    Page<UserDO> listUsers(String keyword, int page, int size);

    /**
     * 删除指定用户
     *
     * @param userId 用户ID
     * @return 是否删除成功
     */
    boolean deleteUser(Long userId);

    /**
     * 重置用户的TOTP认证密钥
     *
     * @param userId 用户ID
     * @return 新生成的TOTP密钥
     */
    String resetTotp(Long userId);

    /**
     * 根据ID查询用户
     *
     * @param userId 用户ID
     * @return 用户对象（密码已脱敏），不存在时返回null
     */
    UserDO findById(Long userId);
}
