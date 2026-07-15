package com.devknow.ticket;

import com.devknow.common.ApiResponse;
import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 工单看板接口：列表 + 人工状态流转（处理人侧使用，乐观锁冲突由全局异常转 409） */
@RestController
@RequestMapping("/api/ticket")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @GetMapping("/list")
    public ApiResponse<List<Ticket>> list() {
        return ApiResponse.ok(ticketService.listByUser(UserContext.require()));
    }

    @PostMapping("/{ticketNo}/transit")
    public ApiResponse<Ticket> transit(@PathVariable String ticketNo, @RequestParam TicketStatus target) {
        return ApiResponse.ok(ticketService.transit(UserContext.require(), ticketNo, target));
    }
}
