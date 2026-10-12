package com.latiendita.productService.category;

import com.latiendita.productService.category.dto.CategoryDto;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping
    public ResponseEntity<CategoryDto> create(@Valid @RequestBody CategoryDto dto) {
        CategoryDto created = categoryService.create(dto);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{categoryId}")
                .buildAndExpand(created.categoryId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping
    public CategoryDto update(@Valid @RequestBody CategoryDto dto) {
        return categoryService.update(dto.categoryId(), dto);
    }

    @PutMapping("/{categoryId}")
    public CategoryDto update(@PathVariable Long categoryId,
                              @Valid @RequestBody CategoryDto dto) {
        return categoryService.update(categoryId, dto);
    }

    @GetMapping
    public Page<CategoryDto> findAll(Pageable pageable) {
        return categoryService.findAll(pageable);
    }

    @GetMapping("/paging")
    public Page<CategoryDto> findAllPaging(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return categoryService.findAllPaging(page, size);
    }

    @GetMapping("/paging-and-sorting")
    public Page<CategoryDto> findAllPagingAndSorting(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "categoryId") String sortBy) {
        return categoryService.findAllPagingAndSorting(pageNo, pageSize, sortBy);
    }

    @GetMapping("/{categoryId}")
    public CategoryDto findById(@PathVariable Long categoryId) {
        return categoryService.findById(categoryId);
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> delete(@PathVariable Long categoryId) {
        categoryService.delete(categoryId);
        return ResponseEntity.noContent().build();
    }
}
