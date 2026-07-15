package com.devknow.ticket;

import com.devknow.common.BizException;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private static final Set<String> VALID_CATEGORIES = Set.of("FAULT", "CONSULT", "PERMISSION", "PURCHASE");

    private final TicketRepository ticketRepository;
    private final AssignService assignService;

    @Transactional
    public Ticket create(Long userId, String title, String description) {
        Ticket t = new Ticket();
        t.setTicketNo("TK" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        t.setUserId(userId);
        t.setTitle(title);
        t.setDescription(description);
        t.setStatus(TicketStatus.PENDING);
        return ticketRepository.save(t);
    }

    /** 分类落库：LLM 抽取的分类必须过白名单校验（工具调用可靠性兜底） */
    @Transactional
    public Ticket classify(String ticketNo, String category) {
        if (!VALID_CATEGORIES.contains(category)) {
            throw new BizException("非法分类: " + category + "，必须是 " + VALID_CATEGORIES);
        }
        Ticket t = mustGet(ticketNo);
        t.setCategory(category);
        return ticketRepository.save(t);
    }

    /**
     * 派单：状态机校验 + 最小负载处理人。
     * 乐观锁冲突自动重试 3 次（Spring @Retryable 依赖额外依赖，手动循环更轻量）。
     */
    public Ticket assign(String ticketNo) {
        int retries = 0;
        while (true) {
            try {
                return doAssign(ticketNo);
            } catch (OptimisticLockException e) {
                if (++retries >= 3) {
                    log.error("assign {} failed after {} retries", ticketNo, retries);
                    throw e;
                }
                log.warn("optimistic lock conflict on assign {}, retry {}/3", ticketNo, retries);
            }
        }
    }

    @Transactional
    protected Ticket doAssign(String ticketNo) {
        Ticket t = mustGet(ticketNo);
        t.getStatus().assertCanTransitTo(TicketStatus.PROCESSING);
        Long handlerId = assignService.pickLeastLoaded();
        if (handlerId == null) throw new BizException("当前无可用处理人");
        t.setAssigneeId(handlerId);
        t.setStatus(TicketStatus.PROCESSING);
        return ticketRepository.save(t);
    }

    /**
     * 人工状态流转。
     * 归属校验（修复越权）：工单号可枚举，必须校验操作者身份 ——
     * 仅「工单创建人」或「当前处理人」可流转，否则任意登录用户都能改他人工单状态。
     */
    @Transactional
    public Ticket transit(Long actorId, String ticketNo, TicketStatus target) {
        Ticket t = mustGet(ticketNo);
        boolean isOwner = actorId.equals(t.getUserId());
        boolean isAssignee = t.getAssigneeId() != null && actorId.equals(t.getAssigneeId());
        if (!isOwner && !isAssignee) {
            throw new BizException(403, "无权操作该工单");
        }
        t.getStatus().assertCanTransitTo(target);
        if (target == TicketStatus.RESOLVED || target == TicketStatus.CLOSED) {
            assignService.release(t.getAssigneeId());
        }
        t.setStatus(target);
        return ticketRepository.save(t);
    }

    public Ticket mustGet(String ticketNo) {
        return ticketRepository.findByTicketNo(ticketNo)
                .orElseThrow(() -> new BizException("工单不存在: " + ticketNo));
    }

    public List<Ticket> listByUser(Long userId) {
        return ticketRepository.findByUserIdOrderByIdDesc(userId);
    }
}
