package com.zhishu.ticket;

import com.zhishu.auth.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 派单负载均衡：Redis ZSet 维护「处理人 -> 当前在手工单数」，
 * Lua 脚本原子执行「ZRANGE 取最小负载 → ZINCRBY +1」，避免并发下读-改-写竞态。
 */
@Service
public class AssignService {

    private static final String LOAD_KEY = "ticket:handler:load";

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private DefaultRedisScript<String> pickScript;

    public AssignService(StringRedisTemplate redis, UserRepository userRepository) {
        this.redis = redis;
        this.userRepository = userRepository;
    }

    /** 启动时把所有处理人灌入 ZSet（已存在则不覆盖其当前负载） */
    @PostConstruct
    public void init() {
        this.pickScript = new DefaultRedisScript<>();
        this.pickScript.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/assign_pick_least.lua")));
        this.pickScript.setResultType(String.class);

        userRepository.findByRole("HANDLER").forEach(h ->
                redis.opsForZSet().addIfAbsent(LOAD_KEY, String.valueOf(h.getId()), 0));
    }

    /** 原子取当前负载最小的处理人并 +1 */
    public Long pickLeastLoaded() {
        String handlerId = redis.execute(pickScript, List.of(LOAD_KEY));
        if (handlerId == null || handlerId.isEmpty()) return null;
        return Long.valueOf(handlerId);
    }

    /** 工单解决/关闭时归还负载 */
    public void release(Long handlerId) {
        if (handlerId != null) {
            redis.opsForZSet().incrementScore(LOAD_KEY, String.valueOf(handlerId), -1);
        }
    }
}
