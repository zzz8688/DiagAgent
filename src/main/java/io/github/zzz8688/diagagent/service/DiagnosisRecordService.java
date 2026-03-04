package io.github.zzz8688.diagagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.zzz8688.diagagent.entity.DiagnosisRecord;

import java.util.List;

public interface DiagnosisRecordService extends IService<DiagnosisRecord> {

    void saveDiagnosis(DiagnosisRecord record);

    List<DiagnosisRecord> findRecentRecords(int limit);

    List<DiagnosisRecord> searchByQuery(String query);

    com.baomidou.mybatisplus.extension.plugins.pagination.Page<DiagnosisRecord> findByPage(int pageNum, int pageSize);
}
