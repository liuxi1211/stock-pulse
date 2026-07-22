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
 * 请求日志切面：记录 Controller 层慢请求（耗时 ≥ {@value #SLOW_REQUEST_THRESHOLD_MS}ms）与异常请求。
 * 耗时小于阈值的成功请求不记录日志，以减少日志噪声。
 */
@Slf4j
@Aspect
@Component
public class RequestLogAspect {

    /** 慢请求耗时阈值（毫秒）：仅当请求耗时 ≥ 该值才记录成功日志 */
    private static final long SLOW_REQUEST_THRESHOLD_MS = 1000L;

    /**
     * 环绕通知：成功请求仅记录耗时达到阈值的，异常请求无条件记录（便于排查问题）
     */
    @Around("execution(* com.arthur.stock.controller..*.*(..))")
    public Object logRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            // 耗时小于阈值的成功请求不记录，减少日志噪声
            if (elapsed >= SLOW_REQUEST_THRESHOLD_MS) {
                log.info("<<< {} | {}.{}() | {}ms",
                        describeRequest(), joinPoint.getSignature().getDeclaringTypeName(),
                        joinPoint.getSignature().getName(), elapsed);
            }
            return result;
        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("!!! {} | {}.{}() | {}ms | {}",
                    describeRequest(), joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(), elapsed, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 拼接「HTTP方法 URI」描述；无法获取请求上下文时返回 UNKNOWN。
     */
    private String describeRequest() {
        HttpServletRequest request = getRequest();
        return request != null ? request.getMethod() + " " + request.getRequestURI() : "UNKNOWN";
    }

    /**
     * 获取当前HTTP请求对象
     */
    private HttpServletRequest getRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }
}
