package io.github.zzz8688.diagagent.service.impl;

import io.github.zzz8688.diagagent.dto.LoginDto;
import io.github.zzz8688.diagagent.entity.User;
import io.github.zzz8688.diagagent.mapper.UserMapper;
import io.github.zzz8688.diagagent.service.UserService;
import io.github.zzz8688.diagagent.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    public Map<String, Object> login(LoginDto loginDto) {
        if (StringUtils.isBlank(loginDto.getUsername()) || StringUtils.isBlank(loginDto.getPassword())) {
            throw new RuntimeException("用户名或密码为空");
        }

        User user = userMapper.findByUsername(loginDto.getUsername());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        if (user.getStatus() != 9) {
            throw new RuntimeException("账号已被禁用");
        }

        String pswd = DigestUtils.md5DigestAsHex(loginDto.getPassword().getBytes());
        if (pswd.equals(user.getPassword())) {
            user.setLastLoginTime(LocalDateTime.now());
            userMapper.updateLastLoginTime(user.getId(), user.getLastLoginTime());

            Map<String, Object> map = new HashMap<>();
            map.put("token", JwtUtil.getToken(user.getId(), user.getUsername()));

            user.setPassword("");
            user.setSalt("");
            map.put("user", user);

            return map;
        } else {
            throw new RuntimeException("密码错误");
        }
    }
}
