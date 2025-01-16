package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author vizzinii
 * &#064;create  2025-01-16 14:59
 * 这是第一个拦截器。第一个拦截器功能是对存在的token进行刷新、对token不存在的请求不操作，之后全部放行到第二个拦截器。
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取token
        String token = request.getHeader("authorization");

        // token不存在，放行
        if (StrUtil.isBlank(token)){
            return true;
        }

        // redis获取用户信息
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);

        // 用户是否存在
        if (map.isEmpty()){
            //不存在：放行
            return true;
        }
        // 将查询到的hash数据转换为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);

        // 存在，把用户放到ThreadLocal中
        UserHolder.saveUser(userDTO);

        // 刷新token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 全部放行
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
