package com.pharma.inventory.repository;

import com.pharma.inventory.entity.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findByProductIdAndBatchNoAndLocationId(Long productId, String batchNo, Long locationId);

    /** 同商品同批号（跨仓库）全部批次，用于入库批号效期冲突校验（P1-16）。 */
    List<Stock> findByProductIdAndBatchNo(Long productId, String batchNo);

    /** 商品是否已有库存（软删除引用校验） */
    boolean existsByProductId(Long productId);

    /** 仓库是否已有库存（软删除引用校验） */
    boolean existsByLocationId(Long locationId);

    /**
     * P1-20 单批次行级悲观锁：不合格品隔离扣减、盘点提交、入库 upsert 均按 (商品, 批号, 仓库) 加锁。
     * 库存唯一键为 (product_id, batch_no, location_id)，因此最多命中一行。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.productId = :productId AND s.batchNo = :batchNo " +
            "AND s.locationId = :locationId")
    Optional<Stock> findByProductIdAndBatchNoAndLocationIdForUpdate(@Param("productId") Long productId,
                                                                    @Param("batchNo") String batchNo,
                                                                    @Param("locationId") Long locationId);

    /**
     * P1-20 并发首建：INSERT ... ON CONFLICT DO NOTHING，避免「先查后插」在并发下撞唯一约束 500。
     * 返回 1=本次插入成功；0=并发事务已插入同批次（调用方改为锁读后累加）。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO stock (product_id, batch_no, produce_date, expire_date, location_id, quantity, unit_cost) " +
            "VALUES (:productId, :batchNo, :produceDate, :expireDate, :locationId, :quantity, :unitCost) " +
            "ON CONFLICT (product_id, batch_no, location_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("productId") Long productId,
                       @Param("batchNo") String batchNo,
                       @Param("produceDate") LocalDate produceDate,
                       @Param("expireDate") LocalDate expireDate,
                       @Param("locationId") Long locationId,
                       @Param("quantity") BigDecimal quantity,
                       @Param("unitCost") BigDecimal unitCost);

    /**
     * 锁批查询：对指定仓库内某商品全部批次加行级悲观锁，按 FEFO（expire_date 升序、batch_no 升序）返回。
     * 数量过滤与 R2 效期过滤在业务层完成（保证锁住该仓库所有批次，防止并发超卖）；不跨仓库。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.productId = :productId AND s.locationId = :locationId " +
            "ORDER BY s.expireDate ASC, s.batchNo ASC")
    List<Stock> findByProductIdAndLocationIdForUpdate(@Param("productId") Long productId,
                                                      @Param("locationId") Long locationId);

    /**
     * 非锁查询（#13 事前预览用）：按 FEFO（expire_date 升序、batch_no 升序）返回指定仓库该商品全部批次。
     * 只读预览不得加锁，避免阻塞真实出库；真实出库仍走 {@link #findByProductIdAndLocationIdForUpdate}。
     */
    @Query("SELECT s FROM Stock s WHERE s.productId = :productId AND s.locationId = :locationId " +
            "ORDER BY s.expireDate ASC, s.batchNo ASC")
    List<Stock> findByProductIdAndLocationId(@Param("productId") Long productId,
                                            @Param("locationId") Long locationId);

    @Query("SELECT s FROM Stock s WHERE " +
            "(:productId IS NULL OR s.productId = :productId) " +
            "AND (:batchNo IS NULL OR :batchNo = '' OR s.batchNo LIKE %:batchNo%) " +
            "AND (:warehouseId IS NULL OR s.locationId = :warehouseId) " +
            "AND (:expireBefore IS NULL OR s.expireDate <= :expireBefore)")
    Page<Stock> search(@Param("productId") Long productId,
                       @Param("batchNo") String batchNo,
                       @Param("warehouseId") Long warehouseId,
                       @Param("expireBefore") LocalDate expireBefore,
                       Pageable pageable);

    @Query("SELECT s FROM Stock s WHERE s.quantity > 0 AND s.expireDate <= :expireBefore " +
            "ORDER BY s.expireDate ASC, s.batchNo ASC")
    List<Stock> findNearExpiry(@Param("expireBefore") LocalDate expireBefore);
}
