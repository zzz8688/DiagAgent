package io.github.zzz8688.diagagent.store;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final PdfDocumentLoaderService pdfDocumentLoaderService;
    private final KnowledgeDocumentSplitter knowledgeDocumentSplitter;

    @Value("${knowledge.rag.chunk.max-embedding-input-length:2000}")
    private int maxEmbeddingInputLength;

    public DocumentIngestionService(EmbeddingModel embeddingModel,
                                    @Qualifier("knowledgeEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore,
                                    PdfDocumentLoaderService pdfDocumentLoaderService,
                                    KnowledgeDocumentSplitter knowledgeDocumentSplitter) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.pdfDocumentLoaderService = pdfDocumentLoaderService;
        this.knowledgeDocumentSplitter = knowledgeDocumentSplitter;
    }

    public int ingestPdf(String filePath, String documentId, String documentTitle) {
        log.info("开始处理PDF文档: {}, ID: {}", filePath, documentId);

        String text = pdfDocumentLoaderService.loadPdfText(filePath);

        return ingestText(text, documentId, documentTitle, "PDF");
    }

    public int ingestPdfFromInputStream(java.io.InputStream inputStream, String fileName,
                                        String documentId, String documentTitle) {
        log.info("开始处理PDF文档(输入流): {}, ID: {}", fileName, documentId);

        String text = pdfDocumentLoaderService.loadPdfFromInputStream(inputStream, fileName);

        return ingestText(text, documentId, documentTitle, "PDF");
    }

    public int ingestText(String text, String documentId, String documentTitle, String sourceType) {
        if (text == null || text.isBlank()) {
            log.warn("文档内容为空，跳过向量化: {}", documentId);
            return 0;
        }

        List<TextSegment> segments = knowledgeDocumentSplitter.splitForDocument(text, documentId, documentTitle, sourceType);

        log.info("文档 {} 切分完成，共 {} 个段落", documentId, segments.size());

        int count = 0;
        int skipped = 0;
        for (TextSegment segment : segments) {
            try {
                if (segment.text().length() > maxEmbeddingInputLength) {
                    skipped++;
                    log.warn("文档 {} 存在超长片段，已跳过，长度={}", documentId, segment.text().length());
                    continue;
                }
                Embedding embedding = embeddingModel.embed(segment).content();
                embeddingStore.add(embedding, segment);
                count++;
                log.debug("段落 {}/{} 已向量化", count, segments.size());
            } catch (Exception e) {
                skipped++;
                log.error("段落向量化失败", e);
            }
        }

        log.info("文档 {} 向量化完成，成功处理 {} 个段落，跳过 {} 个段落", documentId, count, skipped);
        return count;
    }

    public int ingestMarkdown(String content, String documentId, String documentTitle) {
        return ingestText(content, documentId, documentTitle, "Markdown");
    }
}
