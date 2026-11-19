package com.pharma.common.importer;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.pharma.common.BusinessException;
import com.pharma.common.SecurityUtils;
import com.pharma.system.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通用 Excel 导入服务（§13.3）：
 * 模板生成、预览校验（不落库）、执行导入（逐行独立事务 + 三种重复策略）。
 * 具体字段校验与落库由各 {@link ImportHandler} 提供。
 */
@Service
public class ImportService {

    private static final int MAX_ROWS = 5000;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int PREVIEW_LIMIT = 20;
    private static final int MAX_DETAIL_LENGTH = 950;

    private final Map<String, ImportHandler<?>> handlers;
    private final TransactionTemplate requiresNew;
    private final AuditLogService auditLogService;

    public ImportService(List<ImportHandler<?>> handlerList,
                         PlatformTransactionManager transactionManager,
                         AuditLogService auditLogService) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(ImportHandler::resource, h -> h));
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.auditLogService = auditLogService;
    }

    /** 生成模板：Sheet0 表头 + 示例行，Sheet1 填写说明。 */
    public void writeTemplate(String resource, OutputStream outputStream) {
        ImportHandler<?> handler = handler(resource);
        writeTemplate(handler, outputStream);
    }

    /** 预览：解析 + 校验（含业务键重复检查），不落库。 */
    public ImportPreviewResult preview(String resource, MultipartFile file) {
        return preview((ImportHandler<?>) handler(resource), file);
    }

    /** 执行导入：逐行独立事务，失败行不影响其他行。 */
    public ImportResult execute(String resource, MultipartFile file, String duplicateStrategy) {
        return execute((ImportHandler<?>) handler(resource), file, DuplicateStrategy.parse(duplicateStrategy));
    }

    private ImportHandler<?> handler(String resource) {
        ImportHandler<?> handler = handlers.get(resource);
        if (handler == null) {
            throw BusinessException.notFound("不支持的导入资源: " + resource);
        }
        return handler;
    }

    @SuppressWarnings("unchecked")
    private <T> void writeTemplate(ImportHandler<?> rawHandler, OutputStream outputStream) {
        ImportHandler<T> handler = (ImportHandler<T>) rawHandler;
        ExcelWriter writer = null;
        try {
            writer = EasyExcel.write(outputStream).build();
            WriteSheet dataSheet = EasyExcel.writerSheet(0, "导入数据")
                    .head(handler.modelClass())
                    .build();
            writer.write(handler.sampleRows(), dataSheet);

            List<List<String>> tipHead = List.of(List.of("字段"), List.of("是否必填"), List.of("说明"));
            WriteSheet tipSheet = EasyExcel.writerSheet(1, "填写说明")
                    .head(tipHead)
                    .build();
            writer.write(handler.instructions(), tipSheet);
        } catch (Exception e) {
            throw BusinessException.badRequest("模板生成失败: " + e.getMessage());
        } finally {
            if (writer != null) {
                writer.finish();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ImportPreviewResult preview(ImportHandler<?> rawHandler, MultipartFile file) {
        ImportHandler<T> handler = (ImportHandler<T>) rawHandler;
        List<ImportRowData<T>> rows = read(handler, file);

        ImportPreviewResult result = new ImportPreviewResult();
        result.setTotal(rows.size());
        List<ImportError> errors = new ArrayList<>();
        List<ImportPreviewRow> preview = new ArrayList<>();
        int validCount = 0;

        for (ImportRowData<T> rowData : rows) {
            List<ImportError> rowErrors = new ArrayList<>(
                    handler.validateRow(rowData.getData(), rowData.getRowNumber()));
            if (rowErrors.isEmpty()) {
                String key = handler.businessKey(rowData.getData());
                if (key != null && handler.existsByKey(key)) {
                    rowErrors.add(new ImportError(rowData.getRowNumber(), handler.keyField(), "已存在: " + key));
                }
            }
            if (rowErrors.isEmpty()) {
                validCount++;
            }
            errors.addAll(rowErrors);
            if (preview.size() < PREVIEW_LIMIT) {
                preview.add(new ImportPreviewRow(rowData.getRowNumber(), rowErrors.isEmpty(),
                        rowData.getData(), rowErrors));
            }
        }
        result.setValidCount(validCount);
        result.setInvalidCount(rows.size() - validCount);
        result.setPreview(preview);
        result.setErrors(errors);
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> ImportResult execute(ImportHandler<?> rawHandler, MultipartFile file, DuplicateStrategy strategy) {
        ImportHandler<T> handler = (ImportHandler<T>) rawHandler;
        List<ImportRowData<T>> rows = read(handler, file);

        ImportResult result = new ImportResult();
        result.setTotal(rows.size());
        List<ImportError> errors = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;

        for (ImportRowData<T> rowData : rows) {
            List<ImportError> rowErrors = handler.validateRow(rowData.getData(), rowData.getRowNumber());
            if (!rowErrors.isEmpty()) {
                failed++;
                errors.addAll(rowErrors);
                continue;
            }
            try {
                ImportHandler.ImportOutcome outcome = requiresNew.execute(
                        status -> handler.apply(rowData.getData(), strategy));
                if (outcome == null) {
                    failed++;
                    errors.add(new ImportError(rowData.getRowNumber(), null, "导入失败"));
                } else if (outcome == ImportHandler.ImportOutcome.INSERTED) {
                    inserted++;
                } else if (outcome == ImportHandler.ImportOutcome.UPDATED) {
                    updated++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
                errors.add(new ImportError(rowData.getRowNumber(), handler.keyField(),
                        com.pharma.common.batch.BatchExecutor.errorMessage(e)));
            }
        }

        result.setInsertedCount(inserted);
        result.setUpdatedCount(updated);
        result.setSkippedCount(skipped);
        result.setSuccessCount(inserted + updated);
        result.setFailedCount(failed);
        result.setErrors(errors);

        audit(handler, file, result);
        return result;
    }

    private <T> List<ImportRowData<T>> read(ImportHandler<T> handler, MultipartFile file) {
        validateFile(file);
        try (InputStream inputStream = file.getInputStream()) {
            CollectingReadListener<T> listener = new CollectingReadListener<>();
            EasyExcel.read(inputStream, handler.modelClass(), listener)
                    .sheet(0)
                    .headRowNumber(1)
                    .doRead();
            List<ImportRowData<T>> rows = listener.getRows();
            if (rows.isEmpty()) {
                throw BusinessException.badRequest("Excel 无数据行");
            }
            if (rows.size() > MAX_ROWS) {
                throw BusinessException.badRequest("单次导入不能超过 " + MAX_ROWS + " 行，当前 " + rows.size() + " 行");
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.badRequest("Excel 解析失败: " + com.pharma.common.batch.BatchExecutor.errorMessage(e));
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请上传 Excel 文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw BusinessException.badRequest("文件不能超过 5MB");
        }
        String filename = file.getOriginalFilename();
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
                throw BusinessException.badRequest("仅支持 .xlsx/.xls 文件");
            }
        }
    }

    private void audit(ImportHandler<?> handler, MultipartFile file, ImportResult result) {
        String detail = "file=" + file.getOriginalFilename()
                + ", total=" + result.getTotal()
                + ", success=" + result.getSuccessCount()
                + ", failed=" + result.getFailedCount()
                + ", skipped=" + result.getSkippedCount()
                + ", inserted=" + result.getInsertedCount()
                + ", updated=" + result.getUpdatedCount();
        if (!result.getErrors().isEmpty()) {
            detail += ", errors=" + result.getErrors().stream()
                    .limit(5)
                    .map(e -> "#" + e.getRow() + ":" + e.getField() + ":" + e.getMessage())
                    .collect(Collectors.joining("; "));
        }
        if (detail.length() > MAX_DETAIL_LENGTH) {
            detail = detail.substring(0, MAX_DETAIL_LENGTH);
        }
        auditLogService.log(SecurityUtils.currentUser(), "IMPORT", handler.objectType(), null, detail);
    }
}
