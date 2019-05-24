package com.xwbing.handler;

import com.google.common.util.concurrent.RateLimiter;
import com.xwbing.annotation.FlowLimiter;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author xiangwb
 * @date 2019/5/24 19:11
 * 算法：滑动窗口计数器，令牌桶，漏铜算法（该切面基于令牌桶）
 * 访问时，开启独立线程以固定速率往桶中存放令牌，直到达到桶中容量。如果客户端从桶中获取不到令牌，直接拒绝访问服务（客户端发送请求大于往桶中存放令牌的速度）
 * 能接受突然高并发请求下，保护服务（秒杀抢购，流量攻击，DDOS等）
 * 不需要在网关层面限流，不是所有服务接口都需要实现限流，一般限流只要针对大流量接口
 */
@Aspect
@Component
@Slf4j
public class FlowLimiterAspect {
    private Map<String, RateLimiter> rateMap = new ConcurrentHashMap<>();

    @Pointcut("execution(public * com.xwbing.controller..*.*(..)) && @annotation(flowLimiter)")
    public void pointCut(FlowLimiter flowLimiter) {
    }

    @Around(value = "pointCut(flowLimiter)", argNames = "pjp,flowLimiter")
    public Object flowLimiter(ProceedingJoinPoint pjp, FlowLimiter flowLimiter) throws Throwable {
        String requestUri = getRequestUri();
        //保证同个请求有唯一的令牌桶
        RateLimiter rateLimiter;
        if (rateMap.containsKey(requestUri)) {
            rateLimiter = rateMap.get(requestUri);
        } else {
            double permitsPerSecond = flowLimiter.permitsPerSecond();
            rateLimiter = RateLimiter.create(permitsPerSecond);
            rateMap.put(requestUri, rateLimiter);
        }
        long timeOut = flowLimiter.timeOut();
        //获取令牌桶中的令牌，如果自规定时间内，没有获取到令牌，则服务降级
        boolean tryAcquire = rateLimiter.tryAcquire(timeOut, TimeUnit.MILLISECONDS);
        if (!tryAcquire) {
            fallback();
            return null;
        }
        return pjp.proceed();
    }

    /**
     * 服务降级
     */
    private void fallback() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletResponse response = attributes.getResponse();
        String requestURI = attributes.getRequest().getRequestURI();
        response.setHeader("Content-Type", "text/html;charset=utf-8");
        try (PrintWriter writer = response.getWriter()) {
            writer.println("服务繁忙！请稍后重试！");
        } catch (IOException e) {
            log.error(requestURI + "限流");
        }
    }

    private String getRequestUri() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes.getRequest().getRequestURI();
    }

}
