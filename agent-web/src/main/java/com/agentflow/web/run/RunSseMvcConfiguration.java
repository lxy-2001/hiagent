package com.agentflow.web.run;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

public final class RunSseMvcConfiguration implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override
            public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                                       HttpServletResponse response, Object handler) {
                Object candidate = request.getAttribute(RunSseService.REQUEST_SUBSCRIPTION);
                if (candidate instanceof RunSseSubscription subscription) subscription.ready();
            }
        }).addPathPatterns("/api/agent/tasks/*/events");
    }
}
