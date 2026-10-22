package com.latiendita.productService.seed;

import com.latiendita.productService.category.CategoryRepository;
import com.latiendita.productService.category.CategoryService;
import com.latiendita.productService.category.dto.CategoryDto;
import com.latiendita.productService.exception.DuplicateSkuException;
import com.latiendita.productService.product.ProductEntity;
import com.latiendita.productService.product.ProductRepository;
import com.latiendita.productService.product.ProductService;
import com.latiendita.productService.product.dto.ProductDto;
import com.latiendita.productService.storage.ImageStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    /** Formatos de foto soportados en {@code seed/images/<SKU>.*}, en orden de sondeo. */
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp", "gif");

    private final CategoryRepository categoryRepository;
    private final CategoryService categoryService;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final ImageStorageService images;
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
        // Después de los productos y fuera del try: subir imágenes es
        // best-effort, igual que el criterio del propio storage.
        seedImages();
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

    /**
     * Sube las fotos de {@code seed/images/<SKU>.*} al storage con clave
     * determinística {@code productos/<SKU>.<ext>} y las asigna como
     * {@code imageUrl} a los productos que todavía no tienen una. Soporta
     * jpg, jpeg, png, webp y gif. Best-effort: si el storage está caído o
     * deshabilitado se loguea y no detiene el arranque.
     */
    private void seedImages() {
        SeedProduct[] products;
        try {
            products = read("products.json", SeedProduct[].class);
        } catch (IOException ex) {
            log.error("[seed] No se pudo leer products.json para las imágenes: {}",
                    ex.getMessage());
            return;
        }
        int stored = 0;
        int assigned = 0;
        int failed = 0;
        for (SeedProduct seed : products) {
            Optional<SeedImage> image = findImage(seed.sku());
            if (image.isEmpty()) {
                failed++;
                log.error("[seed] Imagen de seed inexistente para el SKU {}", seed.sku());
                continue;
            }
            String key = "productos/" + image.get().fileName();
            try (InputStream in = image.get().resource().getInputStream()) {
                // overwrite=true: aunque se limpie el volumen de Azurite, el
                // siguiente arranque repara los blobs sin tocar la base.
                images.upload(key, in, image.get().contentType());
                stored++;
            } catch (Exception ex) {
                failed++;
                log.error("[seed] Error al subir imagen de {}: {}",
                        seed.sku(), ex.getMessage());
                continue;
            }
            Optional<ProductEntity> product = productRepository.findBySku(seed.sku());
            if (product.isPresent() && product.get().getImageUrl() == null) {
                product.get().setImageUrl(key);
                productRepository.save(product.get());
                assigned++;
            }
        }
        log.info("[seed] images: {} subidas, {} asignadas, {} con error",
                stored, assigned, failed);
    }

    /**
     * Busca la foto del SKU en {@code seed/images/<SKU>.*} sondeando los
     * formatos soportados; en {@code APP_SEED_LOCATION} busca en
     * {@code <directorio>/images}. Vacío si no existe ninguna.
     */
    private Optional<SeedImage> findImage(String sku) {
        for (String extension : IMAGE_EXTENSIONS) {
            String fileName = sku + "." + extension;
            Resource resource = location.isBlank()
                    ? resourceLoader.getResource("classpath:seed/images/" + fileName)
                    : new FileSystemResource(Path.of(location, "images", fileName).toString());
            if (resource.exists()) {
                return Optional.of(new SeedImage(fileName, contentTypeFor(extension), resource));
            }
        }
        return Optional.empty();
    }

    private static String contentTypeFor(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
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

    /** Foto localizada en el classpath (o en APP_SEED_LOCATION) para un SKU. */
    private record SeedImage(String fileName, String contentType, Resource resource) {}
}
