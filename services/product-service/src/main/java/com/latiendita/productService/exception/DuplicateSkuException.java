package com.latiendita.productService.exception;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("El SKU '" + sku + "' ya está en uso");
    }
}
