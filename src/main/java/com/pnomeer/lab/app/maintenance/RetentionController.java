package com.pnomeer.lab.app.maintenance;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/maintenance/retention")
public class RetentionController {
    private final RetentionService service;
    public RetentionController(RetentionService service) { this.service = service; }

    @GetMapping
    public RetentionService.Preview preview(@RequestParam int olderThanDays) { return service.preview(olderThanDays); }

    @PostMapping
    public RetentionService.Result apply(@RequestParam int olderThanDays, @RequestParam String token) {
        return service.apply(olderThanDays, token);
    }
}
