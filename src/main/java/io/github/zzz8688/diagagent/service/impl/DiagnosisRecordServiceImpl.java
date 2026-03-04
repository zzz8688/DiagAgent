package io.github.zzz8688.diagagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.zzz8688.diagagent.entity.DiagnosisRecord;
import io.github.zzz8688.diagagent.mapper.DiagnosisRecordMapper;
import io.github.zzz8688.diagagent.service.DiagnosisRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class DiagnosisRecordServiceImpl extends ServiceImpl<DiagnosisRecordMapper, DiagnosisRecord> 
        implements DiagnosisRecordService {

    @Override
    public void saveDiagnosis(DiagnosisRecord record) {
        log.info("保存诊断记录：{}", record.getQuery());

        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        save(record);

        log.info("诊断记录保存成功，ID: {}", record.getId());
    }

    @Override
    public List<DiagnosisRecord> findRecentRecords(int limit) {
        log.debug("查询最近 {} 条诊断记录", limit);

        LambdaQueryWrapper<DiagnosisRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(DiagnosisRecord::getCreatedAt)
               .last("LIMIT " + limit);

        return list(wrapper);
    }

    @Override
    public List<DiagnosisRecord> searchByQuery(String query) {
        log.debug("搜索诊断记录：{}", query);

        LambdaQueryWrapper<DiagnosisRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(DiagnosisRecord::getQuery, query)
               .or()
               .like(DiagnosisRecord::getConclusion, query)
               .orderByDesc(DiagnosisRecord::getCreatedAt);

        return list(wrapper);
    }

    @Override
    public Page<DiagnosisRecord> findByPage(int pageNum, int pageSize) {
        log.debug("分页查询诊断记录：page={}, size={}", pageNum, pageSize);

        Page<DiagnosisRecord> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DiagnosisRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(DiagnosisRecord::getCreatedAt);

        return page(page, wrapper);
    }
}
