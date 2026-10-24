package com.latiendita.productService.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.latiendita.productService.category.CategoryEntity;
import com.latiendita.productService.category.CategoryRepository;
import com.latiendita.productService.exception.CategoryNotFoundException;
import com.latiendita.productService.exception.DuplicateSkuException;
import com.latiendita.productService.exception.InvalidSortException;
import com.latiendita.productService.exception.ProductNotFoundException;
import com.latiendita.productService.product.dto.ProductDto;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    void shouldCreateProduct() {
        ProductDto request = new ProductDto(null, "Notebook", null, "SKU-1",
                new BigDecimal("999.99"), 5, null);

        when(productRepository.existsBySku("SKU-1")).thenReturn(false);
        when(productRepository.save(any(ProductEntity.class))).thenAnswer(invocation -> {
            ProductEntity product = invocation.getArgument(0);
            product.setId(1L);
            return product;
        });

        ProductDto response = productService.create(request);

        assertThat(response.productId()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("Notebook");
        assertThat(response.sku()).isEqualTo("SKU-1");
        assertThat(response.price()).isEqualByComparingTo("999.99");
        assertThat(response.quantity()).isEqualTo(5);
    }

    @Test
    void shouldCreateProductWithValidCategory() {
        ProductDto request = new ProductDto(null, "Notebook", null, "SKU-1",
                new BigDecimal("999.99"), 5, 1L);

        when(productRepository.existsBySku("SKU-1")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(
                CategoryEntity.builder().id(1L).name("Electrónica").build()));
        when(productRepository.save(any(ProductEntity.class))).thenAnswer(i -> i.getArgument(0));

        ProductDto response = productService.create(request);

        assertThat(response.categoryId()).isEqualTo(1L);
    }

    @Test
    void shouldRejectDuplicateSkuOnCreate() {
        when(productRepository.existsBySku("SKU-1")).thenReturn(true);

        assertThatThrownBy(() -> productService.create(
                new ProductDto(null, "Otro", null, "SKU-1", null, null, null)))
                .isInstanceOf(DuplicateSkuException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void shouldRejectUnknownCategoryOnCreate() {
        ProductDto request = new ProductDto(null, "Notebook", null, "SKU-1",
                null, null, 99L);

        when(productRepository.existsBySku("SKU-1")).thenReturn(false);
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void shouldUpdateProduct() {
        ProductEntity existing = ProductEntity.builder()
                .id(1L)
                .title("Viejo")
                .sku("SKU-VIEJO")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.existsBySkuAndIdNot("SKU-NUEVO", 1L)).thenReturn(false);
        when(productRepository.save(any(ProductEntity.class))).thenAnswer(i -> i.getArgument(0));

        ProductDto response = productService.update(1L,
                new ProductDto(1L, "Nuevo", null, "SKU-NUEVO",
                        new BigDecimal("10.00"), 3, null));

        assertThat(response.title()).isEqualTo("Nuevo");
        assertThat(response.sku()).isEqualTo("SKU-NUEVO");
        assertThat(response.quantity()).isEqualTo(3);
    }

    @Test
    void shouldKeepSameSkuOnUpdateWithoutConflict() {
        ProductEntity existing = ProductEntity.builder()
                .id(1L)
                .title("Actual")
                .sku("SKU-1")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductEntity.class))).thenAnswer(i -> i.getArgument(0));

        ProductDto response = productService.update(1L,
                new ProductDto(1L, "Actual editado", null, "SKU-1", null, null, null));

        assertThat(response.sku()).isEqualTo("SKU-1");
        verify(productRepository, never()).existsBySkuAndIdNot("SKU-1", 1L);
    }

    @Test
    void shouldThrowProductNotFoundOnFindById() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findById(99L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void shouldPaginateProducts() {
        when(productRepository.findAll(PageRequest.of(0, 2)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(productService.findAll(PageRequest.of(0, 2))).isEmpty();
    }

    @Test
    void shouldAcceptSpringSortFormat() {
        PageRequest pageable = PageRequest.of(0, 8,
                Sort.by(new Sort.Order(Sort.Direction.DESC, "id")));
        when(productRepository.findAll(pageable))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(productService.findAll(pageable)).isEmpty();
    }

    @Test
    void shouldRejectInvalidSortBeforeQuerying() {
        PageRequest minusPrefix = PageRequest.of(0, 8,
                Sort.by(new Sort.Order(Sort.Direction.ASC, "-id")));

        assertThatThrownBy(() -> productService.findAll(minusPrefix))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("Formato de sort inválido");

        PageRequest mongoStyle = PageRequest.of(0, 8,
                Sort.by(new Sort.Order(Sort.Direction.ASC, "-id:1")));

        assertThatThrownBy(() -> productService.search(null, null, mongoStyle))
                .isInstanceOf(InvalidSortException.class);

        PageRequest unknownProperty = PageRequest.of(0, 8, Sort.by("noExiste"));

        assertThatThrownBy(() -> productService.search(null, null, unknownProperty))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("no soportada");

        verify(productRepository, never()).findAll(any(Pageable.class));
    }
}
