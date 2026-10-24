package com.latiendita.productService.sort;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latiendita.productService.exception.InvalidSortException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class SortValidatorTest {

    private static final Set<String> ALLOWED = Set.of("id", "title", "price");

    @Test
    void shouldAcceptSpringFormatAscendingAndDescending() {
        assertThatCode(() -> SortValidator.validate(Sort.by("id"), ALLOWED))
                .doesNotThrowAnyException();
        assertThatCode(() -> SortValidator.validate(
                Sort.by(new Sort.Order(Sort.Direction.DESC, "price")), ALLOWED))
                .doesNotThrowAnyException();
        assertThatCode(() -> SortValidator.validate(Sort.unsorted(), ALLOWED))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMinusPrefixExplainingSpringFormat() {
        assertThatThrownBy(() -> SortValidator.validate(
                Sort.by(new Sort.Order(Sort.Direction.ASC, "-id")), ALLOWED))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("Formato de sort inválido")
                .hasMessageContaining("prefijo `-`")
                .hasMessageContaining("id,desc");
    }

    @Test
    void shouldRejectMongoStyleSort() {
        assertThatThrownBy(() -> SortValidator.validate(
                Sort.by(new Sort.Order(Sort.Direction.ASC, "-id:1")), ALLOWED))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("Formato de sort inválido")
                .hasMessageContaining("-id:1");
    }

    @Test
    void shouldRejectUnknownPropertyListingValidOnes() {
        assertThatThrownBy(() -> SortValidator.validate(Sort.by("noExiste"), ALLOWED))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("Propiedad de sort no soportada")
                .hasMessageContaining("noExiste")
                .hasMessageContaining("id, price, title");
    }
}
