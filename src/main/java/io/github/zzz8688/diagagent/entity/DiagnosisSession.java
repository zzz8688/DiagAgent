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
@TableName("diagnosis_session")
public class DiagnosisSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String title;

    private String engine;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
