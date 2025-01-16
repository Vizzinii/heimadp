package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author vizzinii
 * &#064;create  2025-01-16 14:59
 * 这是第二个拦截器。第二个拦截器的功能是对校验登录状态和信息。
 */
public class LoginInterceptor implements HandlerInterceptor {


//    private StringRedisTemplate stringRedisTemplate;
//
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
/*        // 获取session
        HttpSession session = request.getSession();

        // 获取session中的用户信息
        Object user = session.getAttribute("user");

        // 用户是否存在
        // 不存在
        if (user == null){
            // 不存在，拦截，状态码：401 未授权
            response.setStatus(401);
            return false;
        }

        // 存在，把用户放到ThreadLocal中
        UserHolder.saveUser((UserDTO) user);

        // 放行
        return true;*/

        //-----------------------------------------------------------------------------------------------------
/*        //获取token
        String token = request.getHeader("authorization");
        //token不存在，拦截
        if (token == null){
            response.setStatus(401);
            return false;
        }
        //redis获取用户信息
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        //用户是否存在
        //不存在，拦截，状态码：401 未授权
        if (map.isEmpty()){
            response.setStatus(401);
            return false;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);
        //存在，把用户放到ThreadLocal中
        UserHolder.saveUser(userDTO);
        //刷新token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return true;

    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        // 移除用户，避免内存泄露
        UserHolder.removeUser();
    }*/

        // 判断是否需要拦截
        // 即判断 threadlocal 中是否有当前用户，有放行，没有拦截
        if (UserHolder.getUser() == null) {
            // thread local中没有当前用户，拦截
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
