package io.github.zzz8688.diagagent.sandbox.audit;

import io.github.zzz8688.diagagent.sandbox.SandboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@RequiredArgsConstructor
public class SandboxAuditService {

    private final SandboxProperties sandboxProperties;
    private final ConcurrentLinkedDeque<SandboxAuditRecord> auditRecords = new ConcurrentLinkedDeque<>();

    public void record(SandboxAuditRecord record) {
        if (record == null) {
            return;
        }
        auditRecords.addFirst(record);
        trimToMaxSize();
    }

    public List<SandboxAuditRecord> recentRecords() {
        return List.copyOf(new ArrayList<>(auditRecords));
    }

    private void trimToMaxSize() {
        int maxSize = Math.max(1, sandboxProperties.getMaxAuditRecords());
        while (auditRecords.size() > maxSize) {
            auditRecords.pollLast();
        }
    }
}
