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
@TableName("diagnosis_record")
public class DiagnosisRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String query;

    private String conclusion;

    private String rootCause;

    private String suggestions;

    private Double confidence;

    private String engine;

    private Boolean verified;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
