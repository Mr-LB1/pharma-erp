package com.pharma.system.service;

import com.pharma.common.PageResult;
import com.pharma.system.entity.AuditLog;
import com.pharma.system.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;
import java.util.Map;

/**
 * 审计日志服务：关键操作留痕（审核、作废、盘点提交等显式调用）。
 * <p>P1-2：关键操作可附带前后值快照（JSON 文本），并自动记录来源 IP（含 Nginx 反代场景）。</p>
 */
@Service
public class AuditLogService {

    private static final int IP_MAX_LENGTH = 64;

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** 兼容旧调用：无前后值。 */
    public void log(String username, String action, String objectType, Long objectId, String detail) {
        log(username, action, objectType, objectId, detail, null, null);
    }

    /**
     * 关键操作留痕（带前后值）。
     *
     * @param beforeValue 操作前快照（JSON 文本，可空）
     * @param afterValue  操作后快照（JSON 文本，可空）
     */
    public void log(String username, String action, String objectType, Long objectId, String detail,
                    String beforeValue, String afterValue) {
        AuditLog log = new AuditLog();
        log.setUsername(username);
        log.setAction(action);
        log.setObjectType(objectType);
        log.setObjectId(objectId);
        log.setDetail(detail);
        log.setIp(currentIp());
        log.setBeforeValue(beforeValue);
        log.setAfterValue(afterValue);
        repository.save(log);
    }

    public PageResult<AuditLog> list(int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<AuditLog> result = repository.findAll(pageable);
        return new PageResult<>(result.getContent(), result.getTotalElements(), p, s);
    }

    /**
     * 当前请求来源 IP：优先 X-Forwarded-For 首段（Nginx/网关场景），其次 X-Real-IP，最后 remoteAddr。
     * 非 Web 线程（后台任务、单测）返回 null，不抛异常。
     */
    String currentIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty() && !"unknown".equalsIgnoreCase(first)) {
                return truncateIp(first);
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return truncateIp(realIp.trim());
        }
        return truncateIp(request.getRemoteAddr());
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private String truncateIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        return ip.length() <= IP_MAX_LENGTH ? ip : ip.substring(0, IP_MAX_LENGTH);
    }

    /**
     * 构造前后值快照 JSON：{@code json("field", value, "other", value2)}。
     * 仅覆盖审计场景需要的标量/集合/Map 值，避免为一个字段引入额外序列化依赖。
     */
    public static String json(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return "{}";
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("审计快照参数必须成对出现（field, value）");
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(String.valueOf(keyValues[i]))).append("\":");
            appendValue(sb, keyValues[i + 1]);
        }
        return sb.append('}').toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Collection<?> collection) {
            sb.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendValue(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":");
                appendValue(sb, entry.getValue());
            }
            sb.append('}');
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
