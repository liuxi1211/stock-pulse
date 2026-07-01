package com.arthur.stock.util;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;

/**
 * TOTP验证码工具类，基于Google Authenticator库生成密钥、验证码验证和otpauth URL生成
 */
public class TotpUtil {

    /** Google Authenticator实例 */
    private static final GoogleAuthenticator GA = new GoogleAuthenticator();
    /** TOTP发行者名称 */
    private static final String ISSUER = "StockWatcher";

    private TotpUtil() {}

    /**
     * 生成新的TOTP密钥
     *
     * @return Base32编码的TOTP密钥
     */
    public static String generateSecret() {
        GoogleAuthenticatorKey key = GA.createCredentials();
        return key.getKey();
    }

    /**
     * 验证TOTP验证码是否正确
     *
     * @param secret TOTP密钥
     * @param code   用户输入的6位验证码
     * @return 验证成功返回true
     */
    public static boolean verify(String secret, int code) {
        return GA.authorize(secret, code);
    }

    /**
     * 生成otpauth协议URL，用于TOTP应用（如Google Authenticator）扫码添加
     *
     * @param username 用户名
     * @param secret   TOTP密钥
     * @return otpauth://totp 格式的URL
     */
    public static String getOtpAuthUrl(String username, String secret) {
        return String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s",
                ISSUER, username, secret, ISSUER);
    }
}
