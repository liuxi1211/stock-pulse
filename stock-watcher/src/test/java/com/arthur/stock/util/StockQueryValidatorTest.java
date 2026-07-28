package com.arthur.stock.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockQueryValidatorTest {

    @Test
    void validatePageAndSize_合法边界_应通过() {
        assertDoesNotThrow(() -> StockQueryValidator.validatePage(1));
        assertDoesNotThrow(() -> StockQueryValidator.validatePage(StockQueryValidator.MAX_PAGE));
        assertDoesNotThrow(() -> StockQueryValidator.validateSize(1));
        assertDoesNotThrow(() -> StockQueryValidator.validateSize(StockQueryValidator.MAX_SIZE));
    }

    @Test
    void validatePageAndSize_越界_应拒绝() {
        assertThrows(IllegalArgumentException.class, () -> StockQueryValidator.validatePage(0));
        assertThrows(IllegalArgumentException.class,
                () -> StockQueryValidator.validatePage(StockQueryValidator.MAX_PAGE + 1));
        assertThrows(IllegalArgumentException.class, () -> StockQueryValidator.validateSize(0));
        assertThrows(IllegalArgumentException.class,
                () -> StockQueryValidator.validateSize(StockQueryValidator.MAX_SIZE + 1));
    }
}
