package com.latiendita.productService.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.latiendita.productService.category.dto.CategoryDto;
import com.latiendita.productService.exception.CategoryNotFoundException;
import com.latiendita.productService.exception.InvalidSortException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    void shouldCreateCategory() {
        when(categoryRepository.save(any(CategoryEntity.class))).thenAnswer(invocation -> {
            CategoryEntity category = invocation.getArgument(0);
            category.setId(1L);
            return category;
        });

        CategoryDto response = categoryService.create(new CategoryDto(null, "Electrónica", "Devices"));

        assertThat(response.categoryId()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Electrónica");
        assertThat(response.description()).isEqualTo("Devices");
    }

    @Test
    void shouldUpdateCategory() {
        CategoryEntity existing = CategoryEntity.builder()
                .id(1L)
                .name("Vieja")
                .description("Vieja desc")
                .build();

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.save(any(CategoryEntity.class))).thenAnswer(i -> i.getArgument(0));

        CategoryDto response = categoryService.update(1L,
                new CategoryDto(1L, "Nueva", "Nueva desc"));

        assertThat(response.name()).isEqualTo("Nueva");
        assertThat(response.description()).isEqualTo("Nueva desc");
    }

    @Test
    void shouldThrowCategoryNotFoundOnFindById() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.findById(99L))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void shouldThrowCategoryNotFoundOnDelete() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.delete(99L))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void shouldMapCategoryIdSortToIdProperty() {
        when(categoryRepository.findAll(PageRequest.of(0, 10,
                org.springframework.data.domain.Sort.by("id")))).thenReturn(
                org.springframework.data.domain.Page.empty());

        Page<CategoryDto> page = categoryService.findAllPagingAndSorting(0, 10, "categoryId");

        assertThat(page).isEmpty();
    }

    @Test
    void shouldRejectInvalidSortBeforeQuerying() {
        PageRequest minusPrefix = PageRequest.of(0, 10,
                Sort.by(new Sort.Order(Sort.Direction.ASC, "-id")));

        assertThatThrownBy(() -> categoryService.findAll(minusPrefix))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("Formato de sort inválido");

        assertThatThrownBy(() -> categoryService.findAllPagingAndSorting(0, 10, "noExiste"))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("no soportada");

        verify(categoryRepository, never()).findAll(any(Pageable.class));
    }
}
