package io.github.zzz8688.diagagent.mcp.server;

import io.github.zzz8688.diagagent.controller.KnowledgeBaseController;
import io.github.zzz8688.diagagent.skills.SkillDoc;
import io.github.zzz8688.diagagent.skills.SkillService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DiagAgentMcpResourceExporter {

    private static final Logger log = LoggerFactory.getLogger(DiagAgentMcpResourceExporter.class);

    private final SkillService skillService;
    private final KnowledgeBaseController knowledgeBaseController;

    public List<DiagAgentMcpResource> listResources() {
        List<DiagAgentMcpResource> resources = new ArrayList<>();

        for (SkillDoc skill : skillService.listVisibleSkills()) {
            resources.add(new DiagAgentMcpResource(
                    "skill://" + skill.id() + "/SKILL.md",
                    skill.id() + "/SKILL.md",
                    defaultText(skill.description(), "Skill body"),
                    "text/markdown",
                    Map.of("source", "skill")
            ));
            for (String resourcePath : skill.resourceFiles()) {
                resources.add(new DiagAgentMcpResource(
                        "skill://" + skill.id() + "/" + resourcePath,
                        skill.id() + "/" + resourcePath,
                        "Skill resource for " + skill.id(),
                        "text/markdown",
                        Map.of("source", "skill")
                ));
            }
        }

        try {
            for (KnowledgeBaseController.KnowledgeFile file : knowledgeBaseController.listAllKnowledgeFiles()) {
                resources.add(new DiagAgentMcpResource(
                        "knowledge://" + file.getFileName(),
                        file.getFileName(),
                        "Knowledge base document",
                        resolveKnowledgeMimeType(file.getFileName()),
                        Map.of("source", "knowledge")
                ));
            }
        } catch (IOException e) {
            log.warn("列出知识库 MCP 资源失败", e);
        }

        return resources.stream()
                .sorted(Comparator.comparing(DiagAgentMcpResource::uri))
                .toList();
    }

    public DiagAgentMcpResourceReadResult readResource(String uri) {
        if (uri == null || uri.isBlank()) {
            return DiagAgentMcpResourceReadResult.error("资源 uri 不能为空");
        }

        if (uri.startsWith("skill://")) {
            return readSkillResource(uri);
        }
        if (uri.startsWith("knowledge://")) {
            return readKnowledgeResource(uri);
        }
        return DiagAgentMcpResourceReadResult.error("不支持的资源 uri: " + uri);
    }

    private DiagAgentMcpResourceReadResult readSkillResource(String uri) {
        String path = uri.substring("skill://".length());
        int separator = path.indexOf('/');
        if (separator <= 0) {
            return DiagAgentMcpResourceReadResult.error("skill 资源 uri 格式错误: " + uri);
        }

        String skillId = path.substring(0, separator);
        String relativePath = path.substring(separator + 1);
        if ("SKILL.md".equalsIgnoreCase(relativePath)) {
            String content = skillService.readSkillBody(skillId);
            if (content == null || content.isBlank()) {
                return DiagAgentMcpResourceReadResult.error("未找到技能正文: " + skillId);
            }
            return DiagAgentMcpResourceReadResult.success(uri, "text/markdown", content);
        }

        String content = skillService.readResource(skillId, relativePath);
        if (content == null || content.isBlank()) {
            return DiagAgentMcpResourceReadResult.error("未找到技能资源: " + uri);
        }
        return DiagAgentMcpResourceReadResult.success(uri, "text/markdown", content);
    }

    private DiagAgentMcpResourceReadResult readKnowledgeResource(String uri) {
        String fileName = uri.substring("knowledge://".length());
        if (fileName.isBlank()) {
            return DiagAgentMcpResourceReadResult.error("knowledge 资源 uri 格式错误: " + uri);
        }

        String content;
        try {
            content = knowledgeBaseController.readKnowledgeFileContent(fileName);
        } catch (IOException e) {
            return DiagAgentMcpResourceReadResult.error("读取知识库资源失败: " + e.getMessage());
        }
        if (content == null || content.isBlank()) {
            return DiagAgentMcpResourceReadResult.error("未找到知识库资源: " + fileName);
        }

        return DiagAgentMcpResourceReadResult.success(uri, resolveKnowledgeMimeType(fileName), content);
    }

    private String resolveKnowledgeMimeType(String fileName) {
        String normalized = defaultText(fileName, "").toLowerCase();
        if (normalized.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "text/markdown";
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
