package io.github.zzz8688.diagagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session_message")
public class SessionMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String role;

    private String content;

    private LocalDateTime createdAt;
}
