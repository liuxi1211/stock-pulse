package com.arthur.stock.interceptor;

import com.arthur.stock.constant.SessionKeys;
import com.arthur.stock.context.UserContext;
import com.arthur.stock.model.Role;
import com.arthur.stock.model.UserDO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器，检查用户登录状态和TOTP验证状态，
 * 未登录或未完成TOTP验证的请求将被拦截并重定向到登录页
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Value("${auth.admin-skip-totp:false}")
    private boolean adminSkipTotp;

    /**
     * 请求预处理：检查用户是否登录且已完成TOTP验证，公开路径直接放行
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        if (isPublicPath(uri)) {
            return true;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            redirectToLogin(request, response);
            return false;
        }

        UserDO user = (UserDO) session.getAttribute(SessionKeys.AUTH_USER);
        if (user == null) {
            redirectToLogin(request, response);
            return false;
        }

        UserContext.set(user);

        boolean skipTotp = adminSkipTotp && user.getRole() == Role.ADMIN;
        if (!skipTotp) {
            if (user.getTotpSecret() != null && !Boolean.TRUE.equals(session.getAttribute(SessionKeys.TOTP_VERIFIED))) {
                response.sendRedirect("/login/2fa");
                return false;
            }
        }

        return true;
    }

    /**
     * 请求完成后清理UserContext，防止ThreadLocal泄漏
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 判断请求路径是否为公开路径（登录、静态资源、错误页等）
     */
    private boolean isPublicPath(String uri) {
        return uri.startsWith("/login")
                || uri.startsWith("/logout")
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/static/")
                || uri.startsWith("/error")
                || uri.startsWith("/actuator")
                || uri.startsWith("/.well-known/")
                || uri.equals("/favicon.ico")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/webjars/")
                || uri.startsWith("/api/internal/")
                || uri.equals("/tushare/data-init")
                || uri.equals("/tushare/data-init/status");
    }

    /**
     * 将未登录用户重定向到登录页，AJAX请求则返回401状态码和JSON响应
     */
    private void redirectToLogin(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (isAjaxRequest(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"code\":401,\"message\":\"未登录\"}");
        } else {
            String targetUrl = request.getRequestURI();
            if (request.getQueryString() != null) {
                targetUrl += "?" + request.getQueryString();
            }
            request.getSession(true).setAttribute("TARGET_URL", targetUrl);
            response.sendRedirect("/login");
        }
    }

    /**
     * 判断请求是否为AJAX请求（通过X-Requested-With请求头）
     */
    private boolean isAjaxRequest(HttpServletRequest request) {
        return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    }
}
