package io.github.zzz8688.diagagent.mapper;

import io.github.zzz8688.diagagent.entity.DiagnosisRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DiagnosisRecordMapper {

    int insert(DiagnosisRecord record);

    List<DiagnosisRecord> findRecentRecords(@Param("limit") int limit);

    List<DiagnosisRecord> searchByQuery(@Param("query") String query);

    long countAll();

    List<DiagnosisRecord> findByPage(@Param("offset") int offset, @Param("pageSize") int pageSize);

    int deleteById(@Param("id") Long id);

    int deleteAll();
}
