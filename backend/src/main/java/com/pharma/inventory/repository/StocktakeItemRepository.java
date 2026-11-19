package com.pharma.inventory.repository;

import com.pharma.inventory.entity.StocktakeItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StocktakeItemRepository extends JpaRepository<StocktakeItem, Long> {

    List<StocktakeItem> findByStocktakeId(Long stocktakeId);

    List<StocktakeItem> findByStocktakeIdIn(Collection<Long> stocktakeIds);
}
