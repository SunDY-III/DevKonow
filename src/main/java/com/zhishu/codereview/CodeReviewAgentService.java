package com.zhishu.codereview;

import com.zhishu.chat.RedisChatMemoryStore;
import com.zhishu.codeindex.GitRepoManager;
import com.zhishu.governance.TokenAuditService;
import com.zhishu.project.CodeProject;
import com.zhishu.project.CodeProjectRepository;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 代码审查 Agent。
 *
 * <p>低置信路由的回退兜底：
 * 当 RAG 检索找不到具体方法或文档片段时，由此 Agent 接手。
 * Agent 的职责是：理解问题 → 定位相关文件 → scanFile → 分析内容 → 返回结果。
 *
 * <p>这是最终兜底，而不是替代检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewAgentService {

    private final ChatLanguageModel chatModel;
    private final CodeTools codeTools;
    private final CodeProjectRepository projectRepository;
    private final GitRepoManager gitRepoManager;
    private final RedisChatMemoryStore memoryStore;
    private final TokenAuditService tokenAuditService;

    private CodeReviewAgent agent;

    interface CodeReviewAgent {
        @SystemMessage("""
                你是资深代码审查者。用户问了一个问题，但知识库中没有找到准确答案。
                你的任务：
                1. 理解用户真正想问什么（方法定义、调用链、实现逻辑）
                2. 使用 scanFile 工具读取可能包含相关代码的文件
                3. 结合 grpFiles 工具搜索项目中的关键定
                4. 分析后告诉用户：文件中是否有相关代码、在哪、做什么
                """)
        String analyze(@MemoryId String memoryId, @UserMessage String question);
    }

    @PostConstruct
    void init() {
        this.agent = AiServices.builder(CodeReviewAgent.class)
                .chatLanguageModel(chatModel)
                .tools(codeTools)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .chatMemoryStore(memoryStore)
                        .build())
                .build();
    }

    /**
     * 低置信回复时触发代码审查。
     * 将用户问题 + 项目信息注入 Agent，由 Agent 自主决定扫描哪些文件。
     *
     * @param userId         用户ID
     * @param projectId      项目ID（为 null 则尝试从最近项目中推断）
     * @param conversationId 对话ID
     * @param question       用户原始问题
     * @return Agent 的分析结论
     */
    public String analyze(Long userId, Long projectId, String conversationId, String question) {
        codeTools.beginSession();
        long start = System.currentTimeMillis();
        try {
            // 如果有项目信息，尝试辅助 Agent 定位文件
            String contextualQuestion = enhanceQuestionWithProjectInfo(projectId, question);

            String reply = agent.analyze("review:" + conversationId, contextualQuestion);
            tokenAuditService.record(userId, "AGENT",
                    Math.max(1, question.length() / 2),
                    Math.max(1, reply.length() / 2));
            return reply;

        } catch (Exception e) {
            log.error("代码审查 Agent 失败", e);
            return "分析时遇到问题：" + e.getMessage() + "。可稍后重试。";
        } finally {
            codeTools.endSession();
            log.info("代码审查 Agent 耗时: {} ms", System.currentTimeMillis() - start);
        }
    }

    /**
     * 给 Agent 的项目上下文提示。
     * 如果知道项目根目录和已有的方法列表，注入到问题中辅助定位。
     */
    private String enhanceQuestionWithProjectInfo(Long projectId, String question) {
        if (projectId == null || projectId == 0L) {
            return question;
        }

        StringBuilder sb = new StringBuilder(question);
        sb.append("\n\n【项目上下文】\n");

        // 项目信息
        CodeProject project = projectRepository.findById(projectId).orElse(null);
        if (project != null) {
            sb.append("项目: ").append(project.getName());
            sb.append(", 语言: ").append(project.getLanguage());
            sb.append(", 文件数: ").append(project.getTotalFiles());
            sb.append('\n');
        }

        // 仓库路径（Agent 的 scanFile 使用绝对路径）
        if (project != null && project.getRepoUrls() != null) {
            try {
                String repoUrl = extractFirstRepoUrl(project);
                if (repoUrl != null) {
                    String repoName = GitRepoManager.extractRepoName(repoUrl);
                    Path repoPath = gitRepoManager.getRepoPath(
                            projectId != null ? projectId : 0L, repoName);
                    sb.append("仓库根目录: ").append(repoPath.toAbsolutePath()).append('\n');
                    sb.append("提示：使用 scanFile 工具时使用绝对路径。\n");
                }
            } catch (Exception e) {
                log.debug("获取仓库路径失败", e);
            }
        }

        return sb.toString();
    }

    private String extractFirstRepoUrl(CodeProject project) {
        if (project.getRepoUrls() == null) return null;
        try {
            @SuppressWarnings("unchecked")
            List<String> urls = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(project.getRepoUrls(), List.class);
            return urls.isEmpty() ? null : urls.get(0);
        } catch (Exception e) {
            String raw = project.getRepoUrls().replaceAll("[\\[\\]\"]", "");
            return raw.split(",")[0].trim();
        }
    }
}
