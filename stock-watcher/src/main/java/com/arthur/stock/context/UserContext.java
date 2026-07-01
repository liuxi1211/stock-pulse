package com.arthur.stock.context;

import com.arthur.stock.model.UserDO;

/**
 * 用户上下文，基于ThreadLocal存储当前请求的用户信息，
 * 由AuthInterceptor在请求前设置，请求结束后清理
 */
public class UserContext {

    /** 当前线程的用户对象 */
    private static final ThreadLocal<UserDO> CURRENT_USER = new ThreadLocal<>();

    /**
     * 设置当前线程的用户对象
     *
     * @param user 用户对象
     */
    public static void set(UserDO user) {
        CURRENT_USER.set(user);
    }

    /**
     * 获取当前线程的用户对象
     *
     * @return 用户对象，未设置时返回null
     */
    public static UserDO get() {
        return CURRENT_USER.get();
    }

    /**
     * 清除当前线程的用户对象，防止ThreadLocal内存泄漏
     */
    public static void clear() {
        CURRENT_USER.remove();
    }

    /**
     * 获取当前用户ID
     *
     * @return 用户ID，未登录时返回null
     */
    public static Long getUserId() {
        UserDO user = get();
        return user != null ? user.getId() : null;
    }

    /**
     * 判断当前用户是否为管理员
     *
     * @return 管理员返回true，否则返回false
     */
    public static boolean isAdmin() {
        UserDO user = get();
        return user != null && user.getRole() == com.arthur.stock.model.Role.ADMIN;
    }
}
