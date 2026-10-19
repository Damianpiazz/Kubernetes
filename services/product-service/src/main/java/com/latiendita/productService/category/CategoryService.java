package com.latiendita.productService.category;

import com.latiendita.productService.category.dto.CategoryDto;
import com.latiendita.productService.category.dto.CategoryMapper;
import com.latiendita.productService.exception.CategoryNotFoundException;
import com.latiendita.productService.exception.DuplicateCategoryException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryDto create(CategoryDto dto) {
        if (categoryRepository.existsByName(dto.name())) {
            throw new DuplicateCategoryException(dto.name());
        }

        CategoryEntity saved = categoryRepository.save(CategoryEntity.builder()
                .name(dto.name())
                .description(dto.description())
                .build());

        return CategoryMapper.toDto(saved);
    }

    public CategoryDto update(Long id, CategoryDto dto) {
        CategoryEntity category = findEntity(id);

        boolean nameChanged = !category.getName().equals(dto.name());
        if (nameChanged && categoryRepository.existsByNameAndIdNot(dto.name(), id)) {
            throw new DuplicateCategoryException(dto.name());
        }

        category.setName(dto.name());
        category.setDescription(dto.description());

        CategoryEntity saved = categoryRepository.save(category);

        return CategoryMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<CategoryDto> findAll(Pageable pageable) {
        return categoryRepository.findAll(pageable).map(CategoryMapper::toDto);
    }

    @Transactional(readOnly = true)
    public Page<CategoryDto> findAllPaging(int page, int size) {
        return categoryRepository.findAll(PageRequest.of(page, size)).map(CategoryMapper::toDto);
    }

    @Transactional(readOnly = true)
    public Page<CategoryDto> findAllPagingAndSorting(int pageNo, int pageSize, String sortBy) {
        String property = "categoryId".equals(sortBy) ? "id" : sortBy;
        return categoryRepository
                .findAll(PageRequest.of(pageNo, pageSize, Sort.by(property)))
                .map(CategoryMapper::toDto);
    }

    @Transactional(readOnly = true)
    public CategoryDto findById(Long id) {
        return CategoryMapper.toDto(findEntity(id));
    }

    public void delete(Long id) {
        CategoryEntity category = findEntity(id);
        categoryRepository.delete(category);
    }

    private CategoryEntity findEntity(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
    }
}
