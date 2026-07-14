package com.zhishu.project;

/**
 * 当前项目上下文（ThreadLocal）。
 *
 * <p>在 Controller 层从请求参数中提取 projectId 并注入此上下文，
 * 后续 Service 层的检索/索引操作自动按此 projectId 隔离数据。
 *
 * <p>⚠️ 判定点 #3：ThreadLocal 线程安全问题：
 * <ul>
 *   <li>@Async 方法不继承 ThreadLocal → 必须显式传参</li>
 *   <li>请求处理完毕后必须在 finally 中 clear()</li>
 *   <li>Tomcat 线程池复用 → 残留值污染下一个请求</li>
 * </ul>
 */
public class ProjectContextHolder {

    private static final ThreadLocal<Long> currentProject = new ThreadLocal<>();

    public static void set(Long projectId) {
        currentProject.set(projectId);
    }

    public static Long get() {
        Long id = currentProject.get();
        return id != null ? id : 0L;  // 默认 0 = "全部项目"
    }

    public static void clear() {
        currentProject.remove();
    }
}
