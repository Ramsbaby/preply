package com.ramsbaby.preply.component;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreplyRateCacheLoaderTest {

    @Test
    void normalize_removesAliasAndSuffix() {
        assertThat(PreplyRateCacheLoader.normalize("Camila S. - Preply lesson"))
                .isEqualTo("camila");
    }

    @Test
    void normalize_trimsAndLowers() {
        assertThat(PreplyRateCacheLoader.normalize("  John   Doe  "))
                .isEqualTo("john doe");
    }
}


