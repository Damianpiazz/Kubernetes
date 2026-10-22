package com.latiendita.productService.product;

import com.latiendita.productService.storage.ImageStorageService;
import com.latiendita.productService.storage.ImageStorageService.StoredImage;
import java.net.URI;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/products/{id}/image — sirve la imagen del producto.
 *
 * <p>Misma URL relativa en local y producción: el gateway ya enruta
 * /api/products/** hacia este servicio, así que el navegador nunca habla
 * directo con Azurite ni con Azure. Si {@code image_url} es una URL externa
 * (http/...) redirige; si es una clave de blob, proxya los bytes con cache.
 */
@RestController
@RequiredArgsConstructor
public class ProductImageController {

    private final ProductService productService;
    private final ImageStorageService images;

    @GetMapping("/api/products/{productId}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long productId) {
        String ref = productService.findById(productId).imageUrl();
        if (ref == null || ref.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(ref))
                    .build();
        }
        Optional<StoredImage> stored = images.download(ref);
        return stored
                .map(s -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                        .header(HttpHeaders.CONTENT_TYPE, s.contentType() != null
                                ? s.contentType()
                                : "application/octet-stream")
                        .body(s.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
