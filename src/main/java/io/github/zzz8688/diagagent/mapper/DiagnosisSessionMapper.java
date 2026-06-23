package io.github.zzz8688.diagagent.mapper;

import io.github.zzz8688.diagagent.entity.DiagnosisSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DiagnosisSessionMapper {

    int insert(DiagnosisSession session);

    List<DiagnosisSession> findAllByUserId(@Param("userId") Long userId);

    DiagnosisSession findBySessionId(@Param("sessionId") String sessionId);

    int updateById(DiagnosisSession session);

    int deleteBySessionId(@Param("sessionId") String sessionId);

    int deleteAllByUserId(@Param("userId") Long userId);
}
