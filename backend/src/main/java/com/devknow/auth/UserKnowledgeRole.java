package com.devknow.auth;

/**
 * 用户在知识库中的职责角色。
 * 不同角色在层级感知 RAG 中有不同的优先级层级。
 */
public enum UserKnowledgeRole {

    /** 架构师：关注原则和架构决策 */
    ARCHITECT(new int[]{1, 2}, new int[]{3}),

    /** 高级开发：关注架构、规范、实现 */
    SENIOR_DEV(new int[]{2, 3, 4}, new int[]{1, 5}),

    /** 一线开发：关注规范、实现、经验 */
    DEVELOPER(new int[]{3, 4, 5}, new int[]{2}),

    /** 质量保障：关注规范和经验 */
    QA(new int[]{3, 5}, new int[]{2, 4}),

    /** 运维：关注实现和经验 */
    DEVOPS(new int[]{4, 5}, new int[]{2, 3}),

    /** 项目经理/产品：关注原则和架构 */
    PM(new int[]{1, 2}, new int[]{3, 5}),

    /** 未设置角色（默认：全层级平等） */
    UNSPECIFIED(new int[]{1, 2, 3, 4, 5}, new int[]{});

    private final int[] primaryLevels;
    private final int[] secondaryLevels;

    UserKnowledgeRole(int[] primaryLevels, int[] secondaryLevels) {
        this.primaryLevels = primaryLevels;
        this.secondaryLevels = secondaryLevels;
    }

    public int[] getPrimaryLevels() { return primaryLevels; }
    public int[] getSecondaryLevels() { return secondaryLevels; }

    /** 判断某一层级是否属于该角色的主层级范围 */
    public boolean isPrimary(int level) {
        for (int l : primaryLevels) if (l == level) return true;
        return false;
    }

    /** 判断某一层级是否属于该角色的副层级范围 */
    public boolean isSecondary(int level) {
        for (int l : secondaryLevels) if (l == level) return true;
        return false;
    }
}
