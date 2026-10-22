package com.latiendita.productService.product;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<ProductEntity, Long> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    Optional<ProductEntity> findBySku(String sku);

    Page<ProductEntity> findByCategoryId(Long categoryId, Pageable pageable);

    Page<ProductEntity> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    Page<ProductEntity> findByCategoryIdAndTitleContainingIgnoreCase(
            Long categoryId, String title, Pageable pageable);
}
