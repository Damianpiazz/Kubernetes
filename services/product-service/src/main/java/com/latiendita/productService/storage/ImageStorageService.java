package com.latiendita.productService.storage;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.common.StorageSharedKeyCredential;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Almacenamiento de imágenes de productos en Azure Blob Storage.
 *
 * <p>Local: Azurite (emulador oficial, mismo SDK y endpoint). Producción:
 * cuenta de Azure Blob Storage. La clase es autoconteneda a propósito: guarda
 * y lee por CLAVE (p. ej. {@code productos/<uuid>.png}), no por URL absoluta,
 * de modo que si el media crece a otros dominios o necesita transforms se
 * puede extraer a un servicio propio sin tocar el dato en {@code image_url}.
 *
 * <p>El storage caído NO debe impedir el arranque ni sacar el pod de
 * readiness (mismo criterio que Kafka): la verificación del container es
 * best-effort en {@code @PostConstruct} y se reintenta en cada upload.
 */
@Slf4j
@Service
public class ImageStorageService {

    private final BlobContainerClient container;
    private final String endpoint;

    public ImageStorageService(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.account}") String account,
            @Value("${app.storage.key}") String key,
            @Value("${app.storage.container}") String containerName) {
        this.endpoint = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        if (key == null || key.isBlank()) {
            // Storage deshabilitado: permite arrancar y correr tests sin
            // credenciales (app.storage.key no tiene default en k8s).
            this.container = null;
            log.warn("app.storage.key vacío: storage de imágenes DESHABILITADO");
            return;
        }
        this.container = new BlobServiceClientBuilder()
                .endpoint(this.endpoint)
                .credential(new StorageSharedKeyCredential(account, key))
                .buildClient()
                .getBlobContainerClient(containerName);
    }

    @PostConstruct
    void ensureContainer() {
        if (container == null) {
            return;
        }
        try {
            container.createIfNotExists();
            log.info("Container de imágenes OK: {}", container.getBlobContainerName());
        } catch (RuntimeException e) {
            log.error("No se pudo verificar el container de imágenes (¿storage caído?)", e);
        }
    }

    /**
     * Sube (sobrescribe) un blob y devuelve la clave a guardar en {@code image_url}.
     */
    public String upload(String key, InputStream data, String contentType) {
        containerCreateIfMissing();
        BlobClient blob = container.getBlobClient(key);
        blob.upload(BinaryData.fromStream(data), true);
        if (contentType != null && !contentType.isBlank()) {
            blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
        }
        return key;
    }

    /**
     * Descarga un blob; vacío si no existe (lo usa el endpoint proxy de serving).
     */
    public Optional<StoredImage> download(String key) {
        if (container == null) {
            return Optional.empty();
        }
        try {
            BlobClient blob = container.getBlobClient(key);
            if (!blob.exists()) {
                return Optional.empty();
            }
            BlobProperties props = blob.getProperties();
            return Optional.of(new StoredImage(
                    blob.downloadContent().toBytes(),
                    props.getContentType()));
        } catch (BlobStorageException e) {
            return Optional.empty();
        }
    }

    /**
     * Borra el blob (idempotente: no falla si ya no existe).
     */
    public boolean delete(String key) {
        if (container == null) {
            return false;
        }
        try {
            container.getBlobClient(key).deleteIfExists();
            return true;
        } catch (BlobStorageException e) {
            log.warn("No se pudo borrar el blob {}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * URL absoluta del blob (lectura directa o presigned en el futuro).
     */
    public String url(String key) {
        return endpoint + "/" + key;
    }

    /**
     * Clave de almacenamiento: {@code productos/<uuid>.<ext>} — la usa el
     * flujo de upload, todavía pendiente de definir.
     */
    public String newKey(String originalFilename) {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename
                    .substring(originalFilename.lastIndexOf('.') + 1)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
        }
        return "productos/" + UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
    }

    private void containerCreateIfMissing() {
        if (container == null) {
            throw new IllegalStateException(
                    "Storage de imágenes deshabilitado (app.storage.key vacío)");
        }
        container.createIfNotExists();
    }

    /**
     * Binario + content-type que devuelve el proxy de imagen.
     */
    public record StoredImage(byte[] content, String contentType) {}
}
