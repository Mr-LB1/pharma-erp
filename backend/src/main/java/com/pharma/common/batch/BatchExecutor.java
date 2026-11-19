package com.pharma.common.batch;

import com.pharma.common.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 批量执行器：逐条执行，每条独立事务（REQUIRES_NEW）。
 * 单条失败只记录该条 message，不影响其他记录；事务由外层 TransactionTemplate 控制，
 * 避免同类内 @Transactional 自调用失效。
 */
@Component
public class BatchExecutor {

    private static final int MAX_MESSAGE_LENGTH = 300;

    private final TransactionTemplate requiresNew;

    public BatchExecutor(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 按 items 逐条执行（CREATE / UPDATE）。operation 返回成功后的主键。
     */
    public <T> BatchResult executeItems(List<T> items, Function<T, Long> operation) {
        List<BatchItemResult> results = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return BatchResult.of(results);
        }
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            try {
                Long id = requiresNew.execute(status -> operation.apply(item));
                results.add(BatchItemResult.ok(i, id));
            } catch (Exception e) {
                results.add(BatchItemResult.fail(i, errorMessage(e)));
            }
        }
        return BatchResult.of(results);
    }

    /**
     * 按 ids 逐条执行（DELETE / ENABLE / DISABLE）。operation 返回处理后的主键。
     */
    public BatchResult executeIds(List<Long> ids, Function<Long, Long> operation) {
        List<BatchItemResult> results = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return BatchResult.of(results);
        }
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            try {
                if (id == null) {
                    throw BusinessException.badRequest("id 不能为空");
                }
                Long returned = requiresNew.execute(status -> operation.apply(id));
                results.add(BatchItemResult.ok(i, returned == null ? id : returned));
            } catch (Exception e) {
                results.add(BatchItemResult.fail(i, errorMessage(e)));
            }
        }
        return BatchResult.of(results);
    }

    /** 提取可读错误信息（业务异常原样返回，其他异常取根因 message）。 */
    public static String errorMessage(Throwable e) {
        String message = null;
        if (e instanceof BusinessException) {
            message = e.getMessage();
        } else {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            message = root.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
    }
}
