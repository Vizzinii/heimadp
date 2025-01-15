package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Vizzini
 * @since 2025-01-15
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override

    public Result sendCode(String phone, HttpSession session) {
        //检验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合，返回错误信息
            return Result.fail("手机号格式有误");
        }

        //符合，生成6位验证码
        String code = RandomUtil.randomNumbers(6);

        //session保存验证码
        session.setAttribute("code",code);

        //发送验证码 模拟 log需要类上添加@Slf4j注解
        log.debug("发送短信验证码成功，验证码:{}");
        log.debug(code);

        //返回成功信息
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //检验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合，返回错误信息
            return Result.fail("手机号格式有误");
        }
        //检验验证码
        Object sessionCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if(sessionCode == null || !sessionCode.toString().equals(code)){
            return Result.fail("验证码失效或有误");
        }
        //手机号查询看用户是否存在 select * form tb_user where phone = ?
        //使用mybatis-plus
        User user = query().eq("phone", phone).one();
        //不存在，创建用户
        if (user == null){
            user = createUserWithPhone(phone);
        }
        //用户信息存到session
        session.setAttribute("user",user);
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
