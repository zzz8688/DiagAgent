package io.github.zzz8688.diagagent.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Configuration
@Slf4j
public class KnowledgeBaseRagConfig {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${knowledge.base-path:classpath:knowledge}")
    private String knowledgeBasePath;

    private static final int MAX_SEGMENT_SIZE = 500;
    private static final int OVERLAP_SIZE = 50;

    public KnowledgeBaseRagConfig(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    @Bean
    public ContentRetriever knowledgeBaseRetriever() {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .maxResults(5)
                .minScore(0.7)
                .build();
    }

    @PostConstruct
    public void loadKnowledgeBase() {
        log.info("知识库路径: {}", knowledgeBasePath);
        
        try {
            List<Document> allDocuments = new ArrayList<>();
            
            allDocuments.addAll(loadMarkdownFiles());
            allDocuments.addAll(loadPdfFiles());
            
            if (allDocuments.isEmpty()) {
                log.warn("未找到任何知识库文档!");
                return;
            }
            
            log.info("共加载 {} 个文档", allDocuments.size());
            
            ingestDocuments(allDocuments);
            
            log.info("========== 知识库加载完成 ==========");
        } catch (Exception e) {
            log.error("加载知识库文档失败", e);
        }
    }

    private List<Document> loadMarkdownFiles() throws IOException {
        List<Document> documents = new ArrayList<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:knowledge/*.md");

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) continue;

            log.info("加载 Markdown: {}", filename);
            Document doc = new TextDocumentParser().parse(resource.getInputStream());
            doc.metadata().put("source", filename);
            doc.metadata().put("type", "markdown");
            documents.add(doc);
        }

        return documents;
    }

    private List<Document> loadPdfFiles() throws IOException {
        List<Document> documents = new ArrayList<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:knowledge/*.pdf");

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) continue;

            log.info("加载 PDF: {}", filename);
            Document doc = new ApachePdfBoxDocumentParser().parse(resource.getInputStream());
            doc.metadata().put("source", filename);
            doc.metadata().put("type", "pdf");
            documents.add(doc);
        }

        return documents;
    }

    private void ingestDocuments(List<Document> documents) {
        DocumentSplitter splitter = new DocumentByParagraphSplitter(MAX_SEGMENT_SIZE, OVERLAP_SIZE);
        List<TextSegment> segments = splitter.splitAll(documents);

        for (TextSegment segment : segments) {
            try {
                Response<Embedding> response = embeddingModel.embed(segment);
                if (response != null && response.content() != null) {
                    embeddingStore.add(response.content(), segment);
                } else {
                    log.warn("Failed to generate embedding for knowledge base segment, skipping: {}", segment.text());
                }
            } catch (Exception e) {
                log.error("Error embedding segment: {}", segment.text(), e);
            }
        }
        log.info("已向量化并存储 {} 个文档片段", segments.size());
    }

    public void reloadKnowledgeBase() {
        log.info("========== 重新加载知识库 ==========");
        loadKnowledgeBase();
    }
}
