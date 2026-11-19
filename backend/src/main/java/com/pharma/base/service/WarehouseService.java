package com.pharma.base.service;

import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.StoreRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.EnumValidator;
import com.pharma.common.PageResult;
import com.pharma.common.StatusFilter;
import com.pharma.common.enums.EnableStatus;
import com.pharma.common.enums.TempType;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.repository.StocktakeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseService {

    private final WarehouseRepository repository;
    private final StockRepository stockRepository;
    private final StocktakeRepository stocktakeRepository;
    private final StoreRepository storeRepository;

    public WarehouseService(WarehouseRepository repository,
                            StockRepository stockRepository,
                            StocktakeRepository stocktakeRepository,
                            StoreRepository storeRepository) {
        this.repository = repository;
        this.stockRepository = stockRepository;
        this.stocktakeRepository = stocktakeRepository;
        this.storeRepository = storeRepository;
    }

    public PageResult<Warehouse> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<Warehouse> result = repository.search(keyword, StatusFilter.normalize(status), pageable);
        return new PageResult<>(result.getContent(), result.getTotalElements(), p, s);
    }

    /** 详情只返回启用（未软删）仓库 */
    public Warehouse get(Long id) {
        Warehouse warehouse = requireExisting(id);
        if ("INACTIVE".equals(warehouse.getStatus())) {
            throw BusinessException.notFound("仓库不存在: " + id);
        }
        return warehouse;
    }

    @Transactional
    public Warehouse create(Warehouse warehouse) {
        if (warehouse.getName() == null || warehouse.getName().isBlank()) {
            throw BusinessException.badRequest("仓库名称不能为空");
        }
        validateEnums(warehouse);
        warehouse.setId(null);
        return repository.save(warehouse);
    }

    @Transactional
    public Warehouse update(Long id, Warehouse body) {
        Warehouse existing = requireExisting(id);
        if (body.getName() != null) {
            existing.setName(body.getName());
        }
        if (body.getTempType() != null) {
            existing.setTempType(body.getTempType());
        }
        if (body.getStatus() != null) {
            existing.setStatus(body.getStatus());
        }
        validateEnums(existing);
        return repository.save(existing);
    }

    /** 删除 = 软删除（P0-4）：被库存/盘点/门店引用时拒绝，仅置 status=INACTIVE。 */
    @Transactional
    public void delete(Long id) {
        Warehouse warehouse = requireExisting(id);
        if ("INACTIVE".equals(warehouse.getStatus())) {
            return;
        }
        boolean referenced = stockRepository.existsByLocationId(id)
                || stocktakeRepository.existsByWarehouseId(id)
                || storeRepository.existsByWarehouseId(id);
        if (referenced) {
            throw BusinessException.badRequest("仓库已被库存/盘点/门店引用，无法删除，请改为停用");
        }
        warehouse.setStatus("INACTIVE");
        repository.save(warehouse);
    }

    private Warehouse requireExisting(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("仓库不存在: " + id));
    }

    private void validateEnums(Warehouse w) {
        EnumValidator.validate("tempType", w.getTempType(), TempType.class);
        EnumValidator.validate("status", w.getStatus(), EnableStatus.class);
    }
}
