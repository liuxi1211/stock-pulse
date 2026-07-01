package com.arthur.stock.controller;

import com.arthur.stock.constant.SessionKeys;
import com.arthur.stock.context.UserContext;
import com.arthur.stock.model.Role;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.AuthService;
import com.arthur.stock.util.TotpUtil;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器，处理用户登录、TOTP两步验证、退出等操作
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Value("${auth.admin-skip-totp:false}")
    private boolean adminSkipTotp;

    /**
     * 登录页面，支持展示错误消息和退出提示
     */
    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String error,
                            @RequestParam(required = false) String logout,
                            Model model) {
        if (error != null) {
            model.addAttribute("error", "用户名或密码错误");
        }
        if (logout != null) {
            model.addAttribute("message", "已安全退出");
        }
        return "pages/login";
    }

    /**
     * 处理登录表单提交：验证用户名密码，判断是否需要TOTP验证或首次设置TOTP
     */
    @PostMapping("/login")
    public String doLogin(@RequestParam String username,
                          @RequestParam String password,
                          HttpSession session,
                          Model model) {
        UserDO user = authService.authenticate(username, password);
        if (user == null) {
            model.addAttribute("error", "用户名或密码错误");
            model.addAttribute("username", username);
            return "pages/login";
        }

        session.setAttribute(SessionKeys.AUTH_USER, user);

        if (user.getRole() == Role.ADMIN && adminSkipTotp) {
            session.setAttribute(SessionKeys.TOTP_VERIFIED, true);
            if (!authService.isTotpSetupComplete(user)) {
                authService.generateTotpSecret(user.getId());
            }
            String targetUrl = (String) session.getAttribute("TARGET_URL");
            session.removeAttribute("TARGET_URL");
            return "redirect:" + (targetUrl != null ? targetUrl : "/");
        }

        if (!authService.isTotpSetupComplete(user)) {
            String secret = authService.generateTotpSecret(user.getId());
            user.setTotpSecret(secret);
            session.setAttribute(SessionKeys.AUTH_USER, user);
            model.addAttribute("otpAuthUrl", TotpUtil.getOtpAuthUrl(username, secret));
            model.addAttribute("secret", secret);
            return "pages/setup-totp";
        }

        return "redirect:/login/2fa";
    }

    /**
     * TOTP两步验证页面
     */
    @GetMapping("/login/2fa")
    public String totpPage(@RequestParam(required = false) String error, Model model) {
        if (error != null) {
            model.addAttribute("error", "验证码错误，请重试");
        }
        return "pages/login-2fa";
    }

    /**
     * 验证TOTP验证码，验证成功后标记会话为已验证并跳转目标页面
     */
    @PostMapping("/login/verify-totp")
    public String verifyTotp(@RequestParam int code, HttpSession session, Model model) {
        UserDO user = (UserDO) session.getAttribute(SessionKeys.AUTH_USER);
        if (user == null) {
            return "redirect:/login";
        }

        if (authService.verifyTotp(user.getTotpSecret(), code)) {
            session.setAttribute(SessionKeys.TOTP_VERIFIED, true);
            String targetUrl = (String) session.getAttribute("TARGET_URL");
            session.removeAttribute("TARGET_URL");
            return "redirect:" + (targetUrl != null ? targetUrl : "/");
        }

        model.addAttribute("error", "验证码错误，请重试");
        return "pages/login-2fa";
    }

    /**
     * 首次设置TOTP验证，验证成功后标记会话为已验证并跳转目标页面
     */
    @PostMapping("/login/setup-totp")
    public String setupTotp(@RequestParam int code, HttpSession session, Model model) {
        UserDO user = (UserDO) session.getAttribute(SessionKeys.AUTH_USER);
        if (user == null) {
            return "redirect:/login";
        }

        if (authService.verifyTotp(user.getTotpSecret(), code)) {
            session.setAttribute(SessionKeys.TOTP_VERIFIED, true);
            String targetUrl = (String) session.getAttribute("TARGET_URL");
            session.removeAttribute("TARGET_URL");
            return "redirect:" + (targetUrl != null ? targetUrl : "/");
        }

        model.addAttribute("error", "验证码错误，请重新输入");
        model.addAttribute("otpAuthUrl", TotpUtil.getOtpAuthUrl(user.getUsername(), user.getTotpSecret()));
        model.addAttribute("secret", user.getTotpSecret());
        return "pages/setup-totp";
    }

    /**
     * 退出登录，清除会话并跳转登录页
     */
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login?logout";
    }
}
