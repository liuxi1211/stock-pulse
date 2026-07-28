package com.arthur.stock.service;

import com.arthur.stock.dto.tushare.StkHoldernumberDTO;

import java.util.List;

public interface StkHoldernumberService {

    int fetchAndSave(String tsCode);

    int fetchAndSaveAll();

    List<StkHoldernumberDTO> queryRecent(String tsCode, int limit);
}
