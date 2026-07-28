package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.StkHoldertradeDTO;

import java.util.List;

public interface StkHoldertradeService {

    int fetchAndSave(String tsCode, String startDate, String endDate);

    int fetchAndSaveAll(String startDate, String endDate);

    List<StkHoldertradeDTO> queryByDateRange(String tsCode, String startDate, String endDate);
}
