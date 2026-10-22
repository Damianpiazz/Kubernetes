package com.latiendita.productService.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.latiendita.productService.category.CategoryRepository;
import com.latiendita.productService.category.CategoryService;
import com.latiendita.productService.product.ProductEntity;
import com.latiendita.productService.product.ProductRepository;
import com.latiendita.productService.product.ProductService;
import com.latiendita.productService.storage.ImageStorageService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CatalogSeedRunnerTest {

    private static final String PRODUCTS_JSON = """
            [
              { "title": "Remera básica blanca", "sku": "REM-BAS-BLA", "price": 12999.00,
                "quantity": 50, "category": "Remeras", "imageUrl": null }
            ]""";

    private static final String NO_EXISTE = "jpg-inexistente.jpg";

    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private CategoryService categoryService;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductService productService;
    @Mock
    private ImageStorageService images;
    @Mock
    private ResourceLoader resourceLoader;

    private CatalogSeedRunner runner;

    @BeforeEach
    void setUp() {
        runner = new CatalogSeedRunner(categoryRepository, categoryService, productRepository,
                productService, images, new ObjectMapper(), resourceLoader);
        ReflectionTestUtils.setField(runner, "location", "");
    }

    @Test
    void deberiaSubirLaImagenYAsignarlaAlProductoSinImagen() {
        givenSeedJsons();
        givenImage("jpg", new ByteArrayResource(new byte[] {1, 2, 3}));
        when(productRepository.existsBySku("REM-BAS-BLA")).thenReturn(true);
        ProductEntity product = ProductEntity.builder().sku("REM-BAS-BLA").build();
        when(productRepository.findBySku("REM-BAS-BLA")).thenReturn(Optional.of(product));

        runner.run(new DefaultApplicationArguments());

        verify(images).upload(eq("productos/REM-BAS-BLA.jpg"), any(InputStream.class),
                eq("image/jpeg"));
        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getImageUrl()).isEqualTo("productos/REM-BAS-BLA.jpg");
    }

    @Test
    void deberiaDetectarFormatoPngCuandoSoloHayPng() {
        givenSeedJsons();
        givenImage("jpg", new FileSystemResource(NO_EXISTE));
        givenImage("jpeg", new FileSystemResource(NO_EXISTE));
        givenImage("png", new ByteArrayResource(new byte[] {1, 2, 3}));
        when(productRepository.existsBySku("REM-BAS-BLA")).thenReturn(true);
        ProductEntity product = ProductEntity.builder().sku("REM-BAS-BLA").build();
        when(productRepository.findBySku("REM-BAS-BLA")).thenReturn(Optional.of(product));

        runner.run(new DefaultApplicationArguments());

        verify(images).upload(eq("productos/REM-BAS-BLA.png"), any(InputStream.class),
                eq("image/png"));
        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getImageUrl()).isEqualTo("productos/REM-BAS-BLA.png");
    }

    @Test
    void deberiaNoTocarLaBaseCuandoElProductoYaTieneImagen() {
        givenSeedJsons();
        givenImage("jpg", new ByteArrayResource(new byte[] {1, 2, 3}));
        when(productRepository.existsBySku("REM-BAS-BLA")).thenReturn(true);
        ProductEntity product = ProductEntity.builder()
                .sku("REM-BAS-BLA")
                .imageUrl("productos/REM-BAS-BLA.jpg")
                .build();
        when(productRepository.findBySku("REM-BAS-BLA")).thenReturn(Optional.of(product));

        runner.run(new DefaultApplicationArguments());

        verify(images).upload(eq("productos/REM-BAS-BLA.jpg"), any(InputStream.class),
                eq("image/jpeg"));
        verify(productRepository, never()).save(any());
    }

    @Test
    void deberiaNoRevientarElArranqueCuandoElStorageFalla() {
        givenSeedJsons();
        givenImage("jpg", new ByteArrayResource(new byte[] {1, 2, 3}));
        when(productRepository.existsBySku("REM-BAS-BLA")).thenReturn(true);
        doThrow(new IllegalStateException("storage caído")).when(images).upload(any(), any(), any());

        assertThatCode(() -> runner.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    private void givenSeedJsons() {
        when(resourceLoader.getResource("classpath:seed/categories.json"))
                .thenReturn(new ByteArrayResource("[]".getBytes(StandardCharsets.UTF_8)));
        when(resourceLoader.getResource("classpath:seed/products.json"))
                .thenReturn(new ByteArrayResource(PRODUCTS_JSON.getBytes(StandardCharsets.UTF_8)));
    }

    private void givenImage(String extension, Resource resource) {
        when(resourceLoader.getResource("classpath:seed/images/REM-BAS-BLA." + extension))
                .thenReturn(resource);
    }
}
