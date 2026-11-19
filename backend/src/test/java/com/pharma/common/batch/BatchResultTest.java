package com.pharma.common.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量结果汇总单元测试。
 */
class BatchResultTest {

    @Test
    void 汇总成功失败数与逐条结果() {
        BatchResult result = BatchResult.of(List.of(
                BatchItemResult.ok(0, 1L),
                BatchItemResult.fail(1, "商品不存在: 2"),
                BatchItemResult.ok(2, 3L)));

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getResults()).extracting(BatchItemResult::getIndex).containsExactly(0, 1, 2);
        assertThat(result.getResults().get(1).getMessage()).isEqualTo("商品不存在: 2");
    }

    @Test
    void 空结果汇总为零() {
        BatchResult result = BatchResult.of(List.of());
        assertThat(result.getTotal()).isZero();
        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailedCount()).isZero();
    }
}
