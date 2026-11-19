package com.pharma.inventory.repository;

import com.pharma.inventory.entity.Stocktake;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StocktakeRepository extends JpaRepository<Stocktake, Long> {

    @Query("SELECT s FROM Stocktake s WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR s.stocktakeNo LIKE %:keyword%)")
    Page<Stocktake> search(@Param("keyword") String keyword, Pageable pageable);

    boolean existsByWarehouseId(Long warehouseId);
}
