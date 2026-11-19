package com.pharma.base.service;

import com.pharma.base.dto.StoreLicenseWarning;
import com.pharma.base.entity.Store;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.event.StoreChangedEvent;
import com.pharma.base.repository.StoreRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.EnumValidator;
import com.pharma.common.PageResult;
import com.pharma.common.StatusFilter;
import com.pharma.common.enums.EnableStatus;
import com.pharma.common.enums.StoreType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class StoreService {

    private final StoreRepository repository;
    private final WarehouseRepository warehouseRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StoreService(StoreRepository repository,
                        WarehouseRepository warehouseRepository,
                        ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.warehouseRepository = warehouseRepository;
        this.eventPublisher = eventPublisher;
    }

    public PageResult<Store> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<Store> result = repository.search(keyword, StatusFilter.normalize(status), pageable);
        fillWarehouseNames(result.getContent());
        return new PageResult<>(result.getContent(), result.getTotalElements(), p, s);
    }

    /** 详情只返回启用（未软删）门店 */
    public Store get(Long id) {
        Store store = requireExisting(id);
        if ("INACTIVE".equals(store.getStatus())) {
            throw BusinessException.notFound("门店不存在: " + id);
        }
        Warehouse warehouse = findWarehouse(store.getWarehouseId());
        store.setWarehouseName(warehouse == null ? null : warehouse.getName());
        return store;
    }

    @Transactional
    public Store create(Store store) {
        String code = store.getCode();
        if (code == null || code.isBlank()) {
            throw BusinessException.badRequest("门店编码不能为空");
        }
        if (store.getName() == null || store.getName().isBlank()) {
            throw BusinessException.badRequest("门店名称不能为空");
        }
        if (repository.existsByCode(code)) {
            throw BusinessException.badRequest("门店编码已存在: " + code);
        }
        Warehouse warehouse = requireWarehouse(store.getWarehouseId());
        validateEnums(store);
        store.setId(null);
        if (store.getType() == null || store.getType().isBlank()) {
            store.setType(StoreType.DIRECT.name());
        }
        Store saved = repository.save(store);
        saved.setWarehouseName(warehouse.getName());
        publish(StoreChangedEvent.Action.CREATE, saved, warehouse);
        return saved;
    }

    @Transactional
    public Store update(Long id, Store body) {
        Store existing = requireExisting(id);
        if (body.getCode() != null && !body.getCode().isBlank()
                && !body.getCode().equals(existing.getCode())) {
            if (repository.existsByCodeAndIdNot(body.getCode(), id)) {
                throw BusinessException.badRequest("门店编码已存在: " + body.getCode());
            }
            existing.setCode(body.getCode());
        }
        Warehouse warehouse = null;
        if (body.getWarehouseId() != null && !body.getWarehouseId().equals(existing.getWarehouseId())) {
            warehouse = requireWarehouse(body.getWarehouseId());
            existing.setWarehouseId(body.getWarehouseId());
        }
        if (body.getName() != null) {
            existing.setName(body.getName());
        }
        if (body.getType() != null) {
            existing.setType(body.getType());
        }
        if (body.getAddress() != null) {
            existing.setAddress(body.getAddress());
        }
        if (body.getContactName() != null) {
            existing.setContactName(body.getContactName());
        }
        if (body.getContactPhone() != null) {
            existing.setContactPhone(body.getContactPhone());
        }
        if (body.getLicenseNo() != null) {
            existing.setLicenseNo(body.getLicenseNo());
        }
        if (body.getLicenseExpire() != null) {
            existing.setLicenseExpire(body.getLicenseExpire());
        }
        if (body.getConfig() != null) {
            existing.setConfig(body.getConfig());
        }
        if (body.getStatus() != null) {
            existing.setStatus(body.getStatus());
        }
        validateEnums(existing);
        Store saved = repository.save(existing);
        Warehouse current = warehouse != null ? warehouse : findWarehouse(saved.getWarehouseId());
        saved.setWarehouseName(current == null ? null : current.getName());
        publish(StoreChangedEvent.Action.UPDATE, saved, current);
        return saved;
    }

    /**
     * 删除 = 软删除（P0-4）：仅置 status=INACTIVE，DELETE 事件使用删除前快照供 POS 下架门店。
     */
    @Transactional
    public void delete(Long id) {
        Store existing = requireExisting(id);
        if ("INACTIVE".equals(existing.getStatus())) {
            return;
        }
        Warehouse warehouse = findWarehouse(existing.getWarehouseId());
        // DELETE 事件使用删除前快照
        publish(StoreChangedEvent.Action.DELETE, existing, warehouse);
        existing.setStatus("INACTIVE");
        repository.save(existing);
    }

    private Store requireExisting(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("门店不存在: " + id));
    }

    /**
     * 证照到期预警：返回 license_expire 距今 <= days 天（默认 90）的门店，按到期日升序，含已过期（daysLeft 为负）。
     */
    public List<StoreLicenseWarning> expiringLicenses(int days) {
        int d = days <= 0 ? 90 : days;
        LocalDate today = LocalDate.now();
        return repository.findExpiringLicenses(today.plusDays(d)).stream()
                .map(s -> new StoreLicenseWarning(s.getId(), s.getCode(), s.getName(), s.getLicenseNo(),
                        s.getLicenseExpire(), ChronoUnit.DAYS.between(today, s.getLicenseExpire())))
                .toList();
    }

    private Warehouse requireWarehouse(Long warehouseId) {
        if (warehouseId == null) {
            throw BusinessException.badRequest("门店必须关联仓库");
        }
        return warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> BusinessException.badRequest("关联仓库不存在: " + warehouseId));
    }

    private Warehouse findWarehouse(Long warehouseId) {
        return warehouseId == null ? null : warehouseRepository.findById(warehouseId).orElse(null);
    }

    /** 批量回填关联仓库名称（前端列表/详情直接展示，省去二次请求）。 */
    private void fillWarehouseNames(List<Store> stores) {
        List<Long> ids = stores.stream()
                .map(Store::getWarehouseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> names = warehouseRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName));
        stores.forEach(s -> s.setWarehouseName(names.get(s.getWarehouseId())));
    }

    private void validateEnums(Store store) {
        EnumValidator.validate("type", store.getType(), StoreType.class);
        EnumValidator.validate("status", store.getStatus(), EnableStatus.class);
    }

    private void publish(StoreChangedEvent.Action action, Store store, Warehouse warehouse) {
        eventPublisher.publishEvent(new StoreChangedEvent(this, store.getId(), store.getCode(), action,
                snapshot(store, warehouse)));
    }

    /** 门店全字段快照，供后续 outbox → POS STORE_MASTER_UPSERT 映射。 */
    private Map<String, Object> snapshot(Store s, Warehouse w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("code", s.getCode());
        m.put("name", s.getName());
        m.put("type", s.getType());
        m.put("warehouseId", s.getWarehouseId());
        // 仓库主数据当前无编码字段，POS 侧以 warehouseId 关联；仓库编码落地后在此填充
        m.put("warehouseCode", null);
        m.put("warehouseName", w == null ? null : w.getName());
        m.put("address", s.getAddress());
        m.put("contactName", s.getContactName());
        m.put("contactPhone", s.getContactPhone());
        m.put("licenseNo", s.getLicenseNo());
        m.put("licenseExpire", s.getLicenseExpire() == null ? null : s.getLicenseExpire().toString());
        m.put("config", s.getConfig());
        m.put("status", s.getStatus());
        return m;
    }
}
