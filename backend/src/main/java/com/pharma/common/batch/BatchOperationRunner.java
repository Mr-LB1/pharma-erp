package com.pharma.common.batch;

import com.pharma.common.BusinessException;
import com.pharma.common.SecurityUtils;
import com.pharma.system.service.AuditLogService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批量操作编排：校验入参 → 逐条独立事务执行 → 写 audit_log（§13.2）。
 * 资源自己的 create/update/delete 逻辑仍由各 Service 提供，这里只做循环与结果汇总。
 */
@Component
public class BatchOperationRunner {

    private static final int MAX_DETAIL_LENGTH = 1000;
    private static final int MAX_FAILURE_SAMPLES = 10;
    private static final int MAX_ID_SAMPLES = 100;

    private final BatchExecutor executor;
    private final BatchPermissionChecker permissionChecker;
    private final AuditLogService auditLogService;

    public BatchOperationRunner(BatchExecutor executor, BatchPermissionChecker permissionChecker,
                                AuditLogService auditLogService) {
        this.executor = executor;
        this.permissionChecker = permissionChecker;
        this.auditLogService = auditLogService;
    }

    public <T> BatchResult run(String resource,
                               BatchRequest<T> request,
                               Function<T, Long> create,
                               Function<T, Long> update,
                               Function<Long, Long> enable,
                               Function<Long, Long> disable,
                               Function<Long, Long> delete) {
        if (request == null || request.getAction() == null) {
            throw BusinessException.badRequest("action 不能为空");
        }
        BatchAction action = request.getAction();
        List<Long> ids = switch (action) {
            case DELETE, ENABLE, DISABLE -> requireIds(request);
            default -> null;
        };
        BatchResult result = switch (action) {
            case CREATE -> executor.executeItems(requireItems(request), create);
            case UPDATE -> executor.executeItems(requireItems(request), update);
            case DELETE -> executor.executeIds(ids, delete);
            case ENABLE -> executor.executeIds(ids, enable);
            case DISABLE -> executor.executeIds(ids, disable);
        };
        audit(resource, action.name(), ids, result);
        return result;
    }

    /**
     * 单据批量提交（§13.1）：逐条调用单据 submit，每条独立事务。
     */
    public BatchResult runSubmit(String resource, List<Long> ids, Function<Long, Long> submit) {
        if (ids == null || ids.isEmpty()) {
            throw BusinessException.badRequest("提交操作要求 ids 不能为空");
        }
        BatchResult result = executor.executeIds(ids, submit);
        audit(resource, "SUBMIT", ids, result);
        return result;
    }

    /**
     * 单据批量作废（§13.1 扩展）：逐条调用单据作废，每条独立事务，部分成功可表达。
     * 逐项校验作废权限码：无权限条目记为失败，不阻断其他条目
     * （HTTP 层 @PreAuthorize 仍先做整体拦截，此处是服务被直接调用时的兜底）。
     */
    public BatchResult runVoid(String resource, String permissionCode, List<Long> ids, String reason,
                               Function<Long, Long> voidOperation) {
        if (ids == null || ids.isEmpty()) {
            throw BusinessException.badRequest("作废操作要求 ids 不能为空");
        }
        BatchResult result = executor.executeIds(ids, id -> {
            if (!permissionChecker.has(permissionCode)) {
                throw BusinessException.forbidden("无权限: " + permissionCode);
            }
            return voidOperation.apply(id);
        });
        audit(resource, "VOID", ids, result, reason);
        return result;
    }

    private <T> List<T> requireItems(BatchRequest<T> request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw BusinessException.badRequest(request.getAction() + " 操作要求 items 不能为空");
        }
        return request.getItems();
    }

    private List<Long> requireIds(BatchRequest<?> request) {
        if (request.getIds() == null || request.getIds().isEmpty()) {
            throw BusinessException.badRequest(request.getAction() + " 操作要求 ids 不能为空");
        }
        return request.getIds();
    }

    private void audit(String resource, String action, List<Long> ids, BatchResult result) {
        audit(resource, action, ids, result, null);
    }

    private void audit(String resource, String action, List<Long> ids, BatchResult result, String reason) {
        String detail = "total=" + result.getTotal()
                + ", success=" + result.getSuccessCount()
                + ", failed=" + result.getFailedCount();
        if (ids != null && !ids.isEmpty()) {
            detail += ", ids=" + ids.stream().map(String::valueOf)
                    .limit(MAX_ID_SAMPLES)
                    .collect(Collectors.joining(","));
        }
        if (reason != null && !reason.isBlank()) {
            detail += ", reason=" + reason.trim();
        }
        List<BatchItemResult> failures = result.getResults().stream()
                .filter(r -> !r.isSuccess())
                .limit(MAX_FAILURE_SAMPLES)
                .toList();
        if (!failures.isEmpty()) {
            detail += ", failures=" + failures.stream()
                    .map(r -> "#" + r.getIndex() + ":" + r.getMessage())
                    .collect(Collectors.joining("; "));
        }
        if (detail.length() > MAX_DETAIL_LENGTH) {
            detail = detail.substring(0, MAX_DETAIL_LENGTH);
        }
        auditLogService.log(SecurityUtils.currentUser(), "BATCH_" + action, resource, null, detail);
    }
}
