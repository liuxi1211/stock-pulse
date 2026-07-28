package com.arthur.stock.service;

import com.arthur.stock.vo.KlineDataVO;

import java.util.List;

public interface KlineService {

    List<KlineDataVO> getKlineData(String stockCode, String period, String adjustment,
                                    String startDate, String endDate);
}
