package io.github.zzz8688.diagagent.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class PdfDocumentLoaderService {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentLoaderService.class);

    public String loadPdfText(String filePath) {
        try (InputStream inputStream = Files.newInputStream(Path.of(filePath))) {
            return loadPdfFromInputStream(inputStream, filePath);
        } catch (IOException e) {
            log.warn("读取 PDF 失败，返回空文本: {}", filePath, e);
            return "";
        }
    }

    public String loadPdfFromInputStream(InputStream inputStream, String sourceName) {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            return new PDFTextStripper().getText(document);
        } catch (Exception e) {
            log.warn("解析 PDF 失败，返回空文本: {}", sourceName, e);
            return "";
        }
    }
}
