package com.latiendita.productService.seed;

import com.latiendita.productService.category.CategoryRepository;
import com.latiendita.productService.category.CategoryService;
import com.latiendita.productService.category.dto.CategoryDto;
import com.latiendita.productService.exception.DuplicateSkuException;
import com.latiendita.productService.product.ProductRepository;
import com.latiendita.productService.product.ProductService;
import com.latiendita.productService.product.dto.ProductDto;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class CatalogSeedRunner implements ApplicationRunner {

    private final CategoryRepository categoryRepository;
    private final CategoryService categoryService;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Value("${app.seed.location:}")
    private String location;

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedCategories();
            seedProducts();
        } catch (IOException ex) {
            log.error("[seed] No se pudieron leer los archivos de seeds: {}", ex.getMessage());
        }
    }

    private void seedCategories() throws IOException {
        SeedCategory[] categories = read("categories.json", SeedCategory[].class);
        int created = 0;
        int skipped = 0;
        for (SeedCategory seed : categories) {
            if (categoryRepository.findByName(seed.name()).isPresent()) {
                log.info("[seed] Categoría {} ya existe; se omite", seed.name());
                skipped++;
                continue;
            }
            try {
                categoryService.create(new CategoryDto(null, seed.name(), seed.description()));
                created++;
            } catch (Exception ex) {
                log.error("[seed] Error al crear categoría {}: {}",
                        seed.name(), ex.getMessage());
            }
        }
        log.info("[seed] categories: {} creadas, {} omitidas", created, skipped);
    }

    private void seedProducts() throws IOException {
        SeedProduct[] products = read("products.json", SeedProduct[].class);
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (SeedProduct seed : products) {
            if (productRepository.existsBySku(seed.sku())) {
                log.info("[seed] Producto {} ya existe; se omite", seed.sku());
                skipped++;
                continue;
            }
            try {
                Long categoryId = categoryRepository.findByName(seed.category())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Categoría inexistente en el JSON: " + seed.category()))
                        .getId();
                productService.create(new ProductDto(null, seed.title(), seed.imageUrl(),
                        seed.sku(), seed.price(), seed.quantity(), categoryId));
                created++;
            } catch (DuplicateSkuException ex) {
                skipped++;
            } catch (Exception ex) {
                failed++;
                log.error("[seed] Error al crear producto {}: {}",
                        seed.sku(), ex.getMessage());
            }
        }
        log.info("[seed] products: {} creados, {} omitidos, {} con error",
                created, skipped, failed);
    }

    private <T> T read(String fileName, Class<T> type) throws IOException {
        Resource resource = location.isBlank()
                ? resourceLoader.getResource("classpath:seed/" + fileName)
                : new FileSystemResource(Path.of(location, fileName).toString());
        try (InputStream in = resource.getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, type);
        }
    }
}
