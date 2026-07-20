package com.devknow.mentor;

import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 护航 API。
 *
 * <ul>
 *   <li>POST /api/mentor/{projectId}/plan - 生成护航学习计划</li>
 *   <li>GET /api/mentor/{projectId}/progress - 获取学习进度</li>
 *   <li>POST /api/mentor/{projectId}/complete - 完成里程碑</li>
 *   <li>GET /api/mentor/{projectId}/achievements - 成就列表</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/mentor")
@RequiredArgsConstructor
public class MentorController {

    private final MentorService mentorService;

    /**
     * 生成护航学习计划。
     */
    @PostMapping("/{projectId}/plan")
    public ResponseEntity<MentorPlan> generatePlan(@PathVariable Long projectId) {
        Long userId = UserContext.require();
        MentorPlan plan = mentorService.generatePlan(projectId, userId);
        return ResponseEntity.ok(plan);
    }

    /**
     * 获取学习进度。
     */
    @GetMapping("/{projectId}/progress")
    public ResponseEntity<MentorProgress> getProgress(@PathVariable Long projectId) {
        Long userId = UserContext.require();
        MentorProgress progress = mentorService.getProgress(projectId, userId);
        return ResponseEntity.ok(progress);
    }

    /**
     * 完成里程碑。
     * body: {"milestoneId": "..."}
     */
    @PostMapping("/{projectId}/complete")
    public ResponseEntity<Map<String, Object>> completeMilestone(
            @PathVariable Long projectId,
            @RequestBody Map<String, String> body) {
        Long userId = UserContext.require();
        String milestoneId = body.get("milestoneId");
        CompletableFuture<MentorAchievement> future = mentorService.completeMilestone(projectId, userId, milestoneId);
        MentorAchievement achievement = future.join();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "achievement", achievement
        ));
    }

    /**
     * 获取成就列表。
     */
    @GetMapping("/{projectId}/achievements")
    public ResponseEntity<List<MentorAchievement>> getAchievements(@PathVariable Long projectId) {
        Long userId = UserContext.require();
        List<MentorAchievement> achievements = mentorService.getAchievements(projectId, userId);
        return ResponseEntity.ok(achievements);
    }
}
