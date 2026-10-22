package com.latiendita.productService.product;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latiendita.productService.TestcontainersConfiguration;
import com.latiendita.productService.category.CategoryEntity;
import com.latiendita.productService.category.CategoryRepository;
import com.latiendita.productService.product.dto.ProductDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
class ProductApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    void shouldCreateProductReturning201() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new ProductDto(null, "Notebook", "http://img.png",
                                "SKU-1", new BigDecimal("999.99"), 5, null))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/products/")))
                .andExpect(jsonPath("$.productId").isNumber())
                .andExpect(jsonPath("$.title").value("Notebook"))
                .andExpect(jsonPath("$.sku").value("SKU-1"));
    }

    @Test
    void shouldCreateProductWithCategoryReturning201() throws Exception {
        CategoryEntity category = categoryRepository.save(
                CategoryEntity.builder().name("Electrónica").build());

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new ProductDto(null, "Notebook", null, "SKU-1",
                                new BigDecimal("999.99"), 5, category.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(category.getId()));
    }

    @Test
    void shouldRejectDuplicateSkuWith409() throws Exception {
        productRepository.save(product("SKU-1", "Existente"));

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new ProductDto(null, "Otro", null, "SKU-1",
                                null, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
    }

    @Test
    void shouldRejectInvalidRequestWith400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new ProductDto(null, "Sin sku", null, "",
                                null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void shouldRejectUnknownCategoryWith404() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new ProductDto(null, "Notebook", null, "SKU-1",
                                null, null, 999999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Categoría no encontrada"));
    }

    @Test
    void shouldReturn404WhenProductDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/products/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Producto no encontrado"));
    }

    @Test
    void shouldPaginateProducts() throws Exception {
        productRepository.saveAll(List.of(
                product("SKU-1", "Prod1"),
                product("SKU-2", "Prod2"),
                product("SKU-3", "Prod3")));

        mockMvc.perform(get("/api/products").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));

        mockMvc.perform(get("/api/products").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void shouldUpdateProductViaPutWithPathVariable() throws Exception {
        ProductEntity saved = productRepository.save(product("SKU-1", "Viejo"));

        mockMvc.perform(put("/api/products/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new ProductDto(null, "Nuevo", null, "SKU-1",
                                new BigDecimal("10.00"), 3, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Nuevo"))
                .andExpect(jsonPath("$.quantity").value(3));
    }

    @Test
    void shouldUpdateProductViaPutWithBodyProductId() throws Exception {
        ProductEntity saved = productRepository.save(product("SKU-1", "Viejo"));

        mockMvc.perform(put("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new ProductDto(saved.getId(), "Nuevo", null, "SKU-1",
                                null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(saved.getId()))
                .andExpect(jsonPath("$.title").value("Nuevo"));
    }

    @Test
    void shouldRejectSkuUsedByAnotherProductOnUpdateWith409() throws Exception {
        productRepository.save(product("SKU-1", "Prod1"));
        ProductEntity other = productRepository.save(product("SKU-2", "Prod2"));

        mockMvc.perform(put("/api/products/{id}", other.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new ProductDto(null, "Prod2", null, "SKU-1",
                                null, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
    }

    @Test
    void shouldDeleteProductThenReturn404() throws Exception {
        ProductEntity saved = productRepository.save(product("SKU-1", "Borrable"));

        mockMvc.perform(delete("/api/products/{id}", saved.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/products/{id}", saved.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldFilterProductsByTitleIgnoringCase() throws Exception {
        productRepository.saveAll(List.of(
                product("SKU-1", "Remera blanca"),
                product("SKU-2", "Jean azul")));

        mockMvc.perform(get("/api/products").param("q", "remera"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Remera blanca"));
    }

    @Test
    void shouldFilterProductsByCategory() throws Exception {
        CategoryEntity category = categoryRepository.save(
                CategoryEntity.builder().name("Ropa").build());
        productRepository.save(productWithCategory("SKU-1", "Remera", category.getId()));
        productRepository.save(product("SKU-2", "Taza"));

        mockMvc.perform(get("/api/products")
                        .param("categoryId", String.valueOf(category.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-1"));
    }

    @Test
    void shouldCombineTitleAndCategoryFilters() throws Exception {
        CategoryEntity ropa = categoryRepository.save(
                CategoryEntity.builder().name("Ropa").build());
        CategoryEntity hogar = categoryRepository.save(
                CategoryEntity.builder().name("Hogar").build());

        productRepository.save(productWithCategory("SKU-1", "Remera blanca", ropa.getId()));
        productRepository.save(productWithCategory("SKU-2", "Jean azul", ropa.getId()));
        productRepository.save(productWithCategory("SKU-3", "Remera de cocina", hogar.getId()));

        mockMvc.perform(get("/api/products")
                        .param("q", "remera")
                        .param("categoryId", String.valueOf(hogar.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-3"));
    }

    @Test
    void shouldTreatBlankQueryAsNoFilter() throws Exception {
        productRepository.saveAll(List.of(
                product("SKU-1", "Prod1"),
                product("SKU-2", "Prod2")));

        mockMvc.perform(get("/api/products").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    private String body(ProductDto dto) throws Exception {
        return objectMapper.writeValueAsString(dto);
    }

    private ProductEntity product(String sku, String title) {
        return ProductEntity.builder()
                .sku(sku)
                .title(title)
                .build();
    }

    private ProductEntity productWithCategory(String sku, String title, Long categoryId) {
        return ProductEntity.builder()
                .sku(sku)
                .title(title)
                .categoryId(categoryId)
                .build();
    }
}
