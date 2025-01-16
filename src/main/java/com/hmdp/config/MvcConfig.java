package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @author vizzinii
 * &#064;create  2025-01-16 14:59
 * 这是配置两个拦截器的配置类。
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 登录拦截器
     * @param registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                //配置放行路径
                .excludePathPatterns(
                        "/voucher/**",
                        "/upload/**",
                        "/user/login",
                        "/user/code",
                        "/shop/**",
                        "/shop-type/**",
                        "/blog/hot"
                ).order(1);
        /**
         * 刷新token拦截器
         */
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}