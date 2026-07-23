package com.devknow.chat.memory;

/**
 * 长期记忆中的事实类别。
 */
public enum FactCategory {
    /** 技术决策（如"选用 PostgreSQL"） */
    DECISION,
    /** 用户偏好（如"偏爱 REST"） */
    PREFERENCE,
    /** 业务需求（如"需支撑 10K QPS"） */
    REQUIREMENT,
    /** 架构约定（如"采用微服务架构"） */
    ARCHITECTURE,
    /** 一般事实 */
    FACT
}
