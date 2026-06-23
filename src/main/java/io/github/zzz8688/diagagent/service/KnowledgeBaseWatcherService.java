package io.github.zzz8688.diagagent.service;

import io.github.zzz8688.diagagent.config.KnowledgeBaseRagConfig;
import io.github.zzz8688.diagagent.entity.ProcessedDocumentFile;
import io.github.zzz8688.diagagent.repository.ProcessedDocumentFileRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseWatcherService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseWatcherService.class);

    private final KnowledgeBaseRagConfig knowledgeBaseRagConfig;
    private final ProcessedDocumentFileRepository processedDocumentFileRepository;

    @Value("${knowledge.base.path:./knowledge}")
    private String knowledgeBasePath;

    private final Map<String, String> fileHashes = new HashMap<>();
    private final AtomicBoolean isReloading = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        initializeHashes();
        log.info("知识库监控服务已启动");
    }

    @Scheduled(fixedDelay = 60000)
    public void checkForKnowledgeBaseChanges() {
        if (isReloading.get()) {
            log.debug("知识库正在重新加载中，跳过本次检查");
            return;
        }

        File dir = new File(knowledgeBasePath);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".md") || name.endsWith(".pdf"));
        
        Set<String> currentFilenames = new HashSet<>();
        boolean hasChanges = false;

        List<ProcessedDocumentFile> processedFiles = processedDocumentFileRepository.findAll();
        log.info("[监控] 检查知识库文件: MongoDB记录={}个", processedFiles.size());

        if (files != null && files.length > 0) {
            log.info("[监控] 当前目录中有 {} 个知识库文件", files.length);
            for (File file : files) {
                String fileName = file.getName();
                currentFilenames.add(fileName);
                String currentHash = calculateFileHash(file);

                if (currentHash == null) {
                    continue;
                }

                Optional<ProcessedDocumentFile> existingDoc = processedFiles.stream()
                    .filter(doc -> doc.getFilename().equals(fileName))
                    .findFirst();

                if (!existingDoc.isPresent()) {
                    log.info("[监控] 检测到新知识库文件: {}", fileName);
                    hasChanges = true;
                } else if (!currentHash.equals(existingDoc.get().getFileHash())) {
                    log.info("[监控] 检测到知识库文件已修改: {}", fileName);
                    hasChanges = true;
                }
            }
        }

        for (ProcessedDocumentFile doc : processedFiles) {
            if (!currentFilenames.contains(doc.getFilename())) {
                log.info("[监控] 检测到知识库文件已删除: {}", doc.getFilename());
                hasChanges = true;
            }
        }

        if (hasChanges) {
            reloadKnowledgeBaseIncremental();
        }
    }

    private String calculateFileHash(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            byte[] hashBytes = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("计算文件hash失败: {}", file.getName(), e);
            return null;
        }
    }

    private void reloadKnowledgeBaseIncremental() {
        if (!isReloading.compareAndSet(false, true)) {
            log.warn("知识库正在重新加载中，跳过本次请求");
            return;
        }

        try {
            log.info("========== 检测到知识库文件变化，开始增量加载 ==========");
            knowledgeBaseRagConfig.loadKnowledgeBaseIncremental();
            initializeHashes();
            log.info("========== 知识库增量加载完成 ==========");
        } catch (Exception e) {
            log.error("知识库增量加载失败", e);
        } finally {
            isReloading.set(false);
        }
    }

    public void initializeHashes() {
        log.info("从MongoDB加载知识库文件处理记录...");
        try {
            fileHashes.clear();
            List<ProcessedDocumentFile> docs = processedDocumentFileRepository.findAll();
            for (ProcessedDocumentFile doc : docs) {
                fileHashes.put(doc.getFilename(), doc.getFileHash());
                log.debug("[初始化] 加载文件记录: {}, hash={}", doc.getFilename(), doc.getFileHash());
            }
            log.info("从MongoDB加载了 {} 个知识库文件的处理记录", fileHashes.size());
        } catch (Exception e) {
            log.error("从MongoDB加载知识库文件处理记录失败: {}", e.getMessage(), e);
        }
    }
}
