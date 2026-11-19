package com.pharma.base.importer;

import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.base.service.WarehouseService;
import com.pharma.common.BusinessException;
import com.pharma.common.enums.TempType;
import com.pharma.common.importer.ImportError;
import com.pharma.common.importer.ImportHandler;
import com.pharma.common.importer.ImportParseUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 仓库导入：name 必填，tempType 枚举，name 作为重复业务键。
 */
@Component
public class WarehouseImportHandler implements ImportHandler<WarehouseImportRow> {

    private final WarehouseService service;
    private final WarehouseRepository repository;

    public WarehouseImportHandler(WarehouseService service, WarehouseRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Override
    public String resource() {
        return "warehouses";
    }

    @Override
    public String objectType() {
        return "warehouse";
    }

    @Override
    public Class<WarehouseImportRow> modelClass() {
        return WarehouseImportRow.class;
    }

    @Override
    public List<WarehouseImportRow> sampleRows() {
        WarehouseImportRow row = new WarehouseImportRow();
        row.setName("示例常温仓");
        row.setTempType("ROOM_TEMP");
        return List.of(row);
    }

    @Override
    public List<List<String>> instructions() {
        return List.of(
                List.of("仓库名称", "是", "重复时按重复策略处理"),
                List.of("温控类型", "否", ImportParseUtils.legalValues(TempType.class))
        );
    }

    @Override
    public List<ImportError> validateRow(WarehouseImportRow row, int rowNumber) {
        List<ImportError> errors = new ArrayList<>();
        ImportParseUtils.required("name", row.getName(), "仓库名称", rowNumber, errors);
        ImportParseUtils.checkEnum("tempType", row.getTempType(), TempType.class, rowNumber, errors);
        return errors;
    }

    @Override
    public String businessKey(WarehouseImportRow row) {
        return ImportParseUtils.trimToNull(row.getName());
    }

    @Override
    public String keyField() {
        return "name";
    }

    @Override
    public boolean existsByKey(String key) {
        return repository.existsByName(key);
    }

    @Override
    public void create(WarehouseImportRow row) {
        service.create(toEntity(row));
    }

    @Override
    public void updateByKey(String key, WarehouseImportRow row) {
        Warehouse existing = repository.findFirstByNameOrderByIdAsc(key)
                .orElseThrow(() -> BusinessException.notFound("仓库不存在: " + key));
        service.update(existing.getId(), toEntity(row));
    }

    private Warehouse toEntity(WarehouseImportRow row) {
        Warehouse warehouse = new Warehouse();
        warehouse.setName(ImportParseUtils.trimToNull(row.getName()));
        warehouse.setTempType(ImportParseUtils.trimToNull(row.getTempType()));
        return warehouse;
    }
}
