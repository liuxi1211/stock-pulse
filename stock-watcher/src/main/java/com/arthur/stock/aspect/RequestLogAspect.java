package com.arthur.stock.aspect;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求日志切面，记录所有Controller层请求的HTTP方法、URI、执行时间和异常信息
 */
@Slf4j
@Aspect
@Component
public class RequestLogAspect {

    /**
     * 环绕通知：记录请求开始、结束和耗时，异常时记录错误信息
     */
    @Around("execution(* com.arthur.stock.controller..*.*(..))")
    public Object logRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = getRequest();
        String method = request != null ? request.getMethod() : "UNKNOWN";
        String uri = request != null ? request.getRequestURI() : "UNKNOWN";
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();

        log.info(">>> {} {} | {}.{}()", method, uri, className, methodName);
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.info("<<< {} {} | {}.{}() | {}ms", method, uri, className, methodName, elapsed);
            return result;
        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("!!! {} {} | {}.{}() | {}ms | {}", method, uri, className, methodName, elapsed, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 获取当前HTTP请求对象
     */
    private HttpServletRequest getRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }
}
