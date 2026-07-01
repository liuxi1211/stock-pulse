package com.arthur.stock.constant;

/**
 * HTTP会话属性键常量
 */
public final class SessionKeys {

    /** 会话中存储已认证用户对象的键 */
    public static final String AUTH_USER = "AUTH_USER";
    /** 会话中标记TOTP验证已通过的键 */
    public static final String TOTP_VERIFIED = "TOTP_VERIFIED";

    private SessionKeys() {}
}
