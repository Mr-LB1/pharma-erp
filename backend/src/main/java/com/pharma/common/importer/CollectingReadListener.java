package com.pharma.common.importer;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 收集 EasyExcel 读取结果，并记录每行在 Excel 中的真实行号（1 起）。
 */
public class CollectingReadListener<T> extends AnalysisEventListener<T> {

    private final List<ImportRowData<T>> rows = new ArrayList<>();

    @Override
    public void invoke(T data, AnalysisContext context) {
        Integer rowIndex = context.readRowHolder().getRowIndex();
        rows.add(new ImportRowData<>(rowIndex == null ? rows.size() + 2 : rowIndex + 1, data));
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // no-op
    }

    public List<ImportRowData<T>> getRows() {
        return rows;
    }
}
