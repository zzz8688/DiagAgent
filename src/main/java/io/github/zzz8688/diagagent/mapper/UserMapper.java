package io.github.zzz8688.diagagent.mapper;

import io.github.zzz8688.diagagent.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface UserMapper {

    User findByUsername(@Param("username") String username);

    int updateLastLoginTime(@Param("id") Long id, @Param("lastLoginTime") LocalDateTime lastLoginTime);
}
