package com.pharma.common.importer;

import com.pharma.common.BusinessException;

import java.util.List;

/**
 * 单类基础资料的导入处理器：提供 Excel 模型、示例、填写说明、校验与落库动作。
 */
public interface ImportHandler<T> {

    /** 资源名（URL 段）：products/suppliers/customers/warehouses/stores。 */
    String resource();

    /** 审计 objectType：product/supplier/customer/warehouse/store。 */
    String objectType();

    /** Excel 行模型（@ExcelProperty）。 */
    Class<T> modelClass();

    /** 模板示例行。 */
    List<T> sampleRows();

    /** 填写说明：每行 [字段, 是否必填, 说明]。 */
    List<List<String>> instructions();

    /** 必填/类型/枚举校验（不含数据库唯一性）。 */
    List<ImportError> validateRow(T row, int rowNumber);

    /** 业务键：商品/门店为 code，供应商/客户/仓库为 name。 */
    String businessKey(T row);

    /** 业务键对应的字段名（用于错误提示）。 */
    String keyField();

    boolean existsByKey(String key);

    /** 新建（复用资源 Service 的 create）。 */
    void create(T row);

    /** 按业务键更新（复用资源 Service 的 update）。 */
    void updateByKey(String key, T row);

    /**
     * 单行导入（在独立事务中调用）：新建 / 按策略跳过或更新。
     */
    default ImportOutcome apply(T row, DuplicateStrategy strategy) {
        String key = businessKey(row);
        if (key != null && existsByKey(key)) {
            return switch (strategy) {
                case SKIP -> ImportOutcome.SKIPPED;
                case ERROR -> throw BusinessException.badRequest(keyField() + " 已存在: " + key);
                case UPDATE -> {
                    updateByKey(key, row);
                    yield ImportOutcome.UPDATED;
                }
            };
        }
        create(row);
        return ImportOutcome.INSERTED;
    }

    enum ImportOutcome {
        INSERTED,
        UPDATED,
        SKIPPED
    }
}
