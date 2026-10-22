package com.latiendita.productService.product;

import com.latiendita.productService.product.dto.ProductDto;
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
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ProductDto> create(@Valid @RequestBody ProductDto dto) {
        ProductDto created = productService.create(dto);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{productId}")
                .buildAndExpand(created.productId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping
    public ProductDto update(@Valid @RequestBody ProductDto dto) {
        return productService.update(dto.productId(), dto);
    }

    @PutMapping("/{productId}")
    public ProductDto update(@PathVariable Long productId,
                             @Valid @RequestBody ProductDto dto) {
        return productService.update(productId, dto);
    }

    /**
     * GET /api/products?q=&categoryId=&page=&size=&sort=
     *
     * Los filtros son opcionales y combinables: sin parámetros se comporta igual
     * que antes. `sort` sólo acepta propiedades de la entidad, porque Spring
     * Data responde 500 ante una propiedad desconocida.
     */
    @GetMapping
    public Page<ProductDto> findAll(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            Pageable pageable) {
        return productService.search(q, categoryId, pageable);
    }

    @GetMapping("/{productId}")
    public ProductDto findById(@PathVariable Long productId) {
        return productService.findById(productId);
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(@PathVariable Long productId) {
        productService.delete(productId);
        return ResponseEntity.noContent().build();
    }
}
