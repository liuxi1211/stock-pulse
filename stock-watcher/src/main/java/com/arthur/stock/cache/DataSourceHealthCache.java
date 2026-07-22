package com.arthur.stock.cache;

import com.arthur.stock.dto.governance.DatasourceVO;
import org.springframework.stereotype.Component;

/**
 * 数据源健康检查结果缓存，存储最近一次检测的结果在内存中。
 */
@Component
public class DataSourceHealthCache {

    private volatile DatasourceVO latest;

    public DatasourceVO getLatest() {
        return latest;
    }

    public void update(DatasourceVO vo) {
        this.latest = vo;
    }
}
