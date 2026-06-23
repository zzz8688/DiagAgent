package io.github.zzz8688.diagagent.service.impl;

import io.github.zzz8688.diagagent.dto.PageResult;
import io.github.zzz8688.diagagent.entity.DiagnosisRecord;
import io.github.zzz8688.diagagent.mapper.DiagnosisRecordMapper;
import io.github.zzz8688.diagagent.service.DiagnosisRecordService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DiagnosisRecordServiceImpl implements DiagnosisRecordService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisRecordServiceImpl.class);

    private final DiagnosisRecordMapper diagnosisRecordMapper;

    @Override
    public void saveDiagnosis(DiagnosisRecord record) {
        log.info("保存诊断记录：{}", record.getQuery());

        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        diagnosisRecordMapper.insert(record);

        log.info("诊断记录保存成功，ID: {}", record.getId());
    }

    @Override
    public List<DiagnosisRecord> findRecentRecords(int limit) {
        log.debug("查询最近 {} 条诊断记录", limit);
        return diagnosisRecordMapper.findRecentRecords(limit);
    }

    @Override
    public List<DiagnosisRecord> searchByQuery(String query) {
        log.debug("搜索诊断记录：{}", query);
        return diagnosisRecordMapper.searchByQuery(query);
    }

    @Override
    public PageResult<DiagnosisRecord> findByPage(int pageNum, int pageSize) {
        log.debug("分页查询诊断记录：page={}, size={}", pageNum, pageSize);
        int offset = Math.max(pageNum - 1, 0) * pageSize;
        List<DiagnosisRecord> records = diagnosisRecordMapper.findByPage(offset, pageSize);
        long total = diagnosisRecordMapper.countAll();
        return new PageResult<>(records, total, pageNum, pageSize);
    }

    @Override
    public void deleteById(Long id) {
        diagnosisRecordMapper.deleteById(id);
    }

    @Override
    public void deleteAll() {
        diagnosisRecordMapper.deleteAll();
    }
}
