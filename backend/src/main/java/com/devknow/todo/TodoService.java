package com.devknow.todo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * TODO CRUD 持久化服务。
 *
 * <p>当前使用内存 ConcurrentHashMap 存储，后续应迁移至 MySQL 持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoService {

    // 模拟数据库存储: key = projectId + ":" + userId -> List<TodoItem>
    private final Map<String, List<TodoItem>> store = new ConcurrentHashMap<>();

    /**
     * 获取项目的 TODO 列表。
     */
    public List<TodoItem> listTodos(Long projectId, Long userId) {
        String key = key(projectId, userId);
        return store.getOrDefault(key, new ArrayList<>()).stream()
                .sorted(Comparator.comparingInt(TodoItem::getSortOrder))
                .collect(Collectors.toList());
    }

    /**
     * 创建 TODO。
     */
    public TodoItem createTodo(Long projectId, Long userId, TodoItem item) {
        String key = key(projectId, userId);
        List<TodoItem> list = store.computeIfAbsent(key, k -> new ArrayList<>());

        item.setId(System.currentTimeMillis() + list.size());
        item.setProjectId(projectId);
        item.setUserId(userId);
        item.setStatus("pending");
        item.setSortOrder(list.size() + 1);
        item.setCreatedAt(new Date());
        item.setUpdatedAt(new Date());
        list.add(item);
        log.info("创建 TODO: projectId={}, title={}", projectId, item.getTitle());
        return item;
    }

    /**
     * 更新 TODO（状态/排序）。
     */
    public TodoItem updateTodo(Long projectId, Long userId, Long todoId, TodoItem update) {
        String key = key(projectId, userId);
        List<TodoItem> list = store.get(key);
        if (list == null) return null;

        for (int i = 0; i < list.size(); i++) {
            TodoItem item = list.get(i);
            if (item.getId().equals(todoId)) {
                if (update.getStatus() != null) item.setStatus(update.getStatus());
                if (update.getSortOrder() != null) item.setSortOrder(update.getSortOrder());
                if (update.getTitle() != null) item.setTitle(update.getTitle());
                item.setUpdatedAt(new Date());
                return item;
            }
        }
        return null;
    }

    /**
     * 删除 TODO。
     */
    public boolean deleteTodo(Long projectId, Long userId, Long todoId) {
        String key = key(projectId, userId);
        List<TodoItem> list = store.get(key);
        if (list == null) return false;
        return list.removeIf(item -> item.getId().equals(todoId));
    }

    private String key(Long projectId, Long userId) {
        return projectId + ":" + userId;
    }
}
