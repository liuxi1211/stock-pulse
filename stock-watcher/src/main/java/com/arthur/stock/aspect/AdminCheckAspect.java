package com.arthur.stock.aspect;

import com.arthur.stock.annotation.RequireAdmin;
import com.arthur.stock.context.UserContext;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * 管理员权限校验切面，拦截带有@RequireAdmin注解的类或方法，非管理员请求抛出FORBIDDEN异常
 */
@Aspect
@Component
public class AdminCheckAspect {

    /**
     * 前置通知：校验当前用户是否为管理员，非管理员抛出BusinessException
     */
    @Before("@annotation(requireAdmin) || @within(requireAdmin)")
    public void checkAdmin(JoinPoint joinPoint, RequireAdmin requireAdmin) {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
