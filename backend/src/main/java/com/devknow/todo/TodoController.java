package com.devknow.todo;

import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TODO CRUD API。
 *
 * <ul>
 *   <li>GET /api/study/{projectId}/todo - TODO 列表</li>
 *   <li>POST /api/study/{projectId}/todo - 创建 TODO</li>
 *   <li>PUT /api/study/{projectId}/todo/{id} - 更新 TODO</li>
 *   <li>DELETE /api/study/{projectId}/todo/{id} - 删除 TODO</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/study")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    @GetMapping("/{projectId}/todo")
    public ResponseEntity<List<TodoItem>> listTodos(@PathVariable Long projectId) {
        Long userId = UserContext.require();
        return ResponseEntity.ok(todoService.listTodos(projectId, userId));
    }

    @PostMapping("/{projectId}/todo")
    public ResponseEntity<TodoItem> createTodo(
            @PathVariable Long projectId,
            @RequestBody TodoItem item) {
        Long userId = UserContext.require();
        return ResponseEntity.ok(todoService.createTodo(projectId, userId, item));
    }

    @PutMapping("/{projectId}/todo/{todoId}")
    public ResponseEntity<TodoItem> updateTodo(
            @PathVariable Long projectId,
            @PathVariable Long todoId,
            @RequestBody TodoItem update) {
        Long userId = UserContext.require();
        TodoItem item = todoService.updateTodo(projectId, userId, todoId, update);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(item);
    }

    @DeleteMapping("/{projectId}/todo/{todoId}")
    public ResponseEntity<Void> deleteTodo(
            @PathVariable Long projectId,
            @PathVariable Long todoId) {
        Long userId = UserContext.require();
        boolean deleted = todoService.deleteTodo(projectId, userId, todoId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
