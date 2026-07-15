package com.devknow.auth;

import com.devknow.common.ApiResponse;
import com.devknow.common.BizException;
import com.devknow.common.UserContext;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public record AuthRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody AuthRequest req) {
        userRepository.findByUsername(req.username()).ifPresent(u -> {
            throw new BizException("用户名已存在");
        });
        User user = new User();
        user.setUsername(req.username());
        user.setPassword(BCrypt.hashpw(req.password(), BCrypt.gensalt()));
        user.setRole("USER");
        userRepository.save(user);
        return login(req);
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody AuthRequest req) {
        User user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new BizException(401, "用户名或密码错误"));
        if (!BCrypt.checkpw(req.password(), user.getPassword())) {
            throw new BizException(401, "用户名或密码错误");
        }
        String token = jwtUtil.generate(user.getId(), user.getRole());
        return ApiResponse.ok(Map.of("token", token, "userId", user.getId(), "username", user.getUsername()));
    }

    /** 获取用户配置文件 */
    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        Long userId = UserContext.require();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));
        return ApiResponse.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole(),
                "knowledgeRole", user.getKnowledgeRole() != null ? user.getKnowledgeRole() : "UNSPECIFIED"
        ));
    }

    /** 设置用户在知识库中的职责角色 */
    @PutMapping("/knowledge-role")
    public ApiResponse<?> setKnowledgeRole(@RequestBody Map<String, String> body) {
        Long userId = UserContext.require();
        String roleStr = body.get("knowledgeRole");
        if (roleStr == null || roleStr.isBlank()) {
            throw new BizException("knowledgeRole 不能为空");
        }
        // 校验角色值合法性
        try {
            UserKnowledgeRole.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            throw new BizException("无效的角色值: " + roleStr + "，可选: "
                    + java.util.Arrays.toString(UserKnowledgeRole.values()));
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));
        user.setKnowledgeRole(roleStr);
        userRepository.save(user);
        return ApiResponse.ok(Map.of("knowledgeRole", roleStr));
    }

    /** 获取所有可选的知识角色 */
    @GetMapping("/knowledge-roles")
    public ApiResponse<Map<String, Object>> listKnowledgeRoles() {
        Map<String, int[]> roles = new java.util.LinkedHashMap<>();
        for (UserKnowledgeRole r : UserKnowledgeRole.values()) {
            roles.put(r.name(), r.getPrimaryLevels());
        }
        return ApiResponse.ok(Map.of("roles", roles));
    }
}
