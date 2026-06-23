package io.github.zzz8688.diagagent.service;

import io.github.zzz8688.diagagent.dto.LoginDto;
import io.github.zzz8688.diagagent.entity.User;

import java.util.Map;

public interface UserService {
    Map<String, Object> login(LoginDto loginDto);
}
