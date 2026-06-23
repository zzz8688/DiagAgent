package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.dto.LoginDto;
import io.github.zzz8688.diagagent.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginDto loginDto) {
        try {
            return Map.of(
                    "success", true,
                    "data", userService.login(loginDto)
            );
        } catch (RuntimeException ex) {
            throw new AuthRequestException(ex.getMessage(), ex);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    private static final class AuthRequestException extends RuntimeException {

        private AuthRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
