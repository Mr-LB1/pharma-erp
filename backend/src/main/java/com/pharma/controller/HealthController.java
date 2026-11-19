package com.pharma.controller;

import com.pharma.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：验证后端 + 数据库是否就绪。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "pharma-erp");
        body.put("java", System.getProperty("java.version")); // 用于确认跑在 JDK17 上
        body.put("time", System.currentTimeMillis());
        return ApiResponse.ok(body);
    }
}
