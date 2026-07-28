package com.arthur.stock.dto.stock;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class StockQueryResultDTO<T> {

    private List<T> items;
    private StockQueryMetaDTO meta;
}
