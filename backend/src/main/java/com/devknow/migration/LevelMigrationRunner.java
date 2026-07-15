package com.devknow.migration;

import com.devknow.knowledge.KnowledgeDocument;
import com.devknow.knowledge.KnowledgeDocumentRepository;
import com.devknow.knowledge.graph.KnowledgeGraphService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 存量文档层级自动分类迁移。
 *
 * <p>对 level=0 的文档调用 LLM 自动分类，并同步到 Neo4j 节点。
 * 通过 app.migration.level-classify 配置开关。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class LevelMigrationRunner implements CommandLineRunner {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeGraphService graphService;
    private final ChatLanguageModel chatModel;
    private final JdbcTemplate jdbcTemplate;

    private static final String CLASSIFY_PROMPT = """
            分析以下文档的文件名和内容摘要，判断它属于哪个知识层级，只返回 JSON。

            层级定义：
            - L1 (原则层)：团队共识、技术原则、价值观
            - L2 (架构层)：架构决策、ADR、系统边界、设计文档
            - L3 (规范层)：编码规范、接口约定、发布流程
            - L4 (实现层)：代码说明、接口细节、配置示例
            - L5 (经验层)：故障复盘、排障 SOP、踩坑记录

            文件名：{{fileName}}

            输出格式：{"level": int, "reason": string}
            注意：只返回 1~5，如果无法判断返回 0
            """;

    @Override
    public void run(String... args) {
        // 检查是否需要迁移：先读取文档中是否有 level=0 的记录
        // 通过 JdbcTemplate 执行原生 SQL，因为 level 字段可能刚加
        try {
            // 先检查 level 列是否存在
            jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = 'devknow' AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'level'",
                    Integer.class);
        } catch (Exception e) {
            log.info("level 列不存在，跳过层级迁移（请先执行 DDL）");
            return;
        }

        Integer unclassified = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document WHERE level IS NULL OR level = 0",
                Integer.class);
        if (unclassified == null || unclassified == 0) {
            log.info("所有文档已有层级，跳过迁移");
            return;
        }

        log.info("开始存量文档层级分类: {} 篇待分类", unclassified);

        // 使用原生 SQL 遍历所有未分类文档
        List<Map<String, Object>> docs = jdbcTemplate.queryForList(
                "SELECT id, file_name FROM knowledge_document WHERE level IS NULL OR level = 0");

        int classified = 0;
        int failed = 0;

        for (Map<String, Object> doc : docs) {
            Long id = ((Number) doc.get("id")).longValue();
            String fileName = (String) doc.get("file_name");
            String contentPreview = fetchDocContent(id);

            try {
                String prompt = PromptTemplate.from(CLASSIFY_PROMPT)
                        .apply(Map.of("fileName", fileName != null ? fileName : ""))
                        .text();

                ChatRequest request = ChatRequest.builder()
                            .messages(UserMessage.from(prompt)).build();
                String response = chatModel.chat(request).aiMessage().text();
                int level = parseLevelResponse(response);

                if (level >= 1 && level <= 5) {
                    jdbcTemplate.update("UPDATE knowledge_document SET level = ? WHERE id = ?",
                            level, id);

                    // 同步到 Neo4j 节点
                    graphService.createOrUpdateNode(id, fileName, level, "");

                    classified++;
                    if (classified % 10 == 0) {
                        log.info("层级迁移进度: {}/{}", classified, unclassified);
                    }
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("文档分类失败（id={}）: {}", id, e.getMessage());
            }
        }

        log.info("层级迁移完成: 成功={}, 失败={}, 总计={}", classified, failed, unclassified);
    }

    private String fetchDocContent(Long docId) {
        try {
            List<String> chunks = jdbcTemplate.queryForList(
                    "SELECT content FROM document_chunk WHERE doc_id = ? LIMIT 1", String.class, docId);
            return chunks.isEmpty() ? "" : chunks.get(0).length() > 200 ? chunks.get(0).substring(0, 200) : chunks.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    private int parseLevelResponse(String response) {
        try {
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.indexOf("```"));
            }
            json = json.trim();
            if (json.contains("\"level\"")) {
                int idx = json.indexOf("\"level\"");
                idx = json.indexOf(':', idx);
                int end = json.indexOf(',', idx);
                if (end < 0) end = json.indexOf('}', idx);
                if (end < 0) end = json.length();
                String num = json.substring(idx + 1, end).trim();
                return Integer.parseInt(num);
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
