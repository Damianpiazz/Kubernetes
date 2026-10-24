package com.latiendita.productService.product;

import com.latiendita.productService.category.CategoryRepository;
import com.latiendita.productService.exception.CategoryNotFoundException;
import com.latiendita.productService.exception.DuplicateSkuException;
import com.latiendita.productService.exception.ProductNotFoundException;
import com.latiendita.productService.product.dto.ProductDto;
import com.latiendita.productService.product.dto.ProductMapper;
import com.latiendita.productService.sort.SortValidator;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {

    /**
     * Propiedades de {@link ProductEntity} que se pueden usar en `sort`. Cualquier
     * otra (o un formato como `-id`) se rechaza con 400 antes de llegar a JPA.
     */
    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("id", "title", "imageUrl", "sku", "price", "quantity", "categoryId");

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductDto create(ProductDto dto) {
        if (productRepository.existsBySku(dto.sku())) {
            throw new DuplicateSkuException(dto.sku());
        }
        validateCategory(dto.categoryId());

        ProductEntity saved = productRepository.save(ProductEntity.builder()
                .title(dto.title())
                .imageUrl(dto.imageUrl())
                .sku(dto.sku())
                .price(dto.price())
                .quantity(dto.quantity())
                .categoryId(dto.categoryId())
                .build());

        return ProductMapper.toDto(saved);
    }

    public ProductDto update(Long id, ProductDto dto) {
        ProductEntity product = findEntity(id);

        boolean skuChanged = !product.getSku().equals(dto.sku());
        if (skuChanged && productRepository.existsBySkuAndIdNot(dto.sku(), id)) {
            throw new DuplicateSkuException(dto.sku());
        }
        validateCategory(dto.categoryId());

        product.setTitle(dto.title());
        product.setImageUrl(dto.imageUrl());
        product.setSku(dto.sku());
        product.setPrice(dto.price());
        product.setQuantity(dto.quantity());
        product.setCategoryId(dto.categoryId());

        ProductEntity saved = productRepository.save(product);

        return ProductMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<ProductDto> findAll(Pageable pageable) {
        SortValidator.validate(pageable.getSort(), SORTABLE_PROPERTIES);
        return productRepository.findAll(pageable).map(ProductMapper::toDto);
    }

    /**
     * Listado con filtros opcionales del catálogo.
     *
     * Se usan queries derivadas (y no una query con parámetros nullables) para
     * no depender de la inferencia de tipos de Hibernate ante un null.
     */
    @Transactional(readOnly = true)
    public Page<ProductDto> search(String q, Long categoryId, Pageable pageable) {
        SortValidator.validate(pageable.getSort(), SORTABLE_PROPERTIES);
        String term = q == null || q.isBlank() ? null : q.trim();
        boolean byCategory = categoryId != null;

        Page<ProductEntity> page;
        if (byCategory && term != null) {
            page = productRepository.findByCategoryIdAndTitleContainingIgnoreCase(
                    categoryId, term, pageable);
        } else if (byCategory) {
            page = productRepository.findByCategoryId(categoryId, pageable);
        } else if (term != null) {
            page = productRepository.findByTitleContainingIgnoreCase(term, pageable);
        } else {
            page = productRepository.findAll(pageable);
        }

        return page.map(ProductMapper::toDto);
    }

    @Transactional(readOnly = true)
    public ProductDto findById(Long id) {
        return ProductMapper.toDto(findEntity(id));
    }

    public void delete(Long id) {
        ProductEntity product = findEntity(id);
        productRepository.delete(product);
    }

    private ProductEntity findEntity(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    private void validateCategory(Long categoryId) {
        if (categoryId != null && categoryRepository.findById(categoryId).isEmpty()) {
            throw new CategoryNotFoundException(categoryId);
        }
    }
}
