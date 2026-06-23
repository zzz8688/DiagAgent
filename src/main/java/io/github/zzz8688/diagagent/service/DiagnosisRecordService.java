package io.github.zzz8688.diagagent.service;

import io.github.zzz8688.diagagent.dto.PageResult;
import io.github.zzz8688.diagagent.entity.DiagnosisRecord;

import java.util.List;

public interface DiagnosisRecordService {

    void saveDiagnosis(DiagnosisRecord record);

    List<DiagnosisRecord> findRecentRecords(int limit);

    List<DiagnosisRecord> searchByQuery(String query);

    PageResult<DiagnosisRecord> findByPage(int pageNum, int pageSize);

    void deleteById(Long id);

    void deleteAll();
}
