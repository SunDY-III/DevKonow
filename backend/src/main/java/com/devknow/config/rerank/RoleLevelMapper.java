package com.devknow.auth;

import org.springframework.stereotype.Component;

/**
 * 角色层级映射器。
 * 将用户角色和 LLM 分类结果结合，决定最终搜索层级范围和置信度调整。
 */
@Component
public class RoleLevelMapper {

    /**
     * 根据用户角色和 LLM 分类结果，调整搜索层级范围和置信度。
     *
     * @param role       用户的知识角色
     * @param llmLevel   LLM 分类的层级（0=无法判断）
     * @param llmConfidence LLM 置信度
     * @return 调整后的检索参数
     */
    public AdjustedPlan adjust(UserKnowledgeRole role, int llmLevel, double llmConfidence) {
        if (role == null || role == UserKnowledgeRole.UNSPECIFIED) {
            // 无角色设定，保持 LLM 原生结果
            return new AdjustedPlan(resolveNativeLevels(llmLevel, llmConfidence),
                    llmConfidence, false);
        }

        // 情况 1: LLM 判断的层级在角色主层级范围内 → 置信度提升
        if (llmLevel >= 1 && llmLevel <= 5 && role.isPrimary(llmLevel)) {
            int[] searchLevels = role.getPrimaryLevels();
            double boosted = Math.min(1.0, llmConfidence * 1.15);  // +15%
            return new AdjustedPlan(searchLevels, boosted, false);
        }

        // 情况 2: LLM 判断的层级在角色副层级范围内 → 略微降权，但仍可用
        if (llmLevel >= 1 && llmLevel <= 5 && role.isSecondary(llmLevel)) {
            return new AdjustedPlan(role.getPrimaryLevels(), llmConfidence * 0.85, false);
        }

        // 情况 3: LLM 无法判断或层级不在角色范围内
        // → 主层级作为 A 路，LLM 结果作为 B 路（降权）
        if (llmLevel >= 1 && llmLevel <= 5) {
            return new AdjustedPlan(role.getPrimaryLevels(), llmConfidence * 0.7, true);
        }

        // 兜底：LLM 完全无法判断，按角色主层级搜索
        return new AdjustedPlan(role.getPrimaryLevels(), 0.5, false);
    }

    private int[] resolveNativeLevels(int level, double confidence) {
        if (confidence >= 0.7) return new int[]{level};
        if (confidence >= 0.4 && level > 0) return expand(level, 1);
        return new int[]{1, 2, 3, 4, 5};
    }

    private int[] expand(int center, int offset) {
        int low = Math.max(1, center - offset);
        int high = Math.min(5, center + offset);
        int[] range = new int[high - low + 1];
        for (int i = 0; i < range.length; i++) range[i] = low + i;
        return range;
    }

    /** 角色调整后的检索计划 */
    public static class AdjustedPlan {
        private final int[] searchLevels;
        private final double adjustedConfidence;
        /** 是否需要用 LLM 原生层级做 B 路补刀 */
        private final boolean needRouteB;

        public AdjustedPlan(int[] searchLevels, double adjustedConfidence, boolean needRouteB) {
            this.searchLevels = searchLevels;
            this.adjustedConfidence = adjustedConfidence;
            this.needRouteB = needRouteB;
        }

        public int[] getSearchLevels() { return searchLevels; }
        public double getAdjustedConfidence() { return adjustedConfidence; }
        public boolean isNeedRouteB() { return needRouteB; }
    }
}
