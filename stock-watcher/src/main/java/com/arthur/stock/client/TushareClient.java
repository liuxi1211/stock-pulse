package com.arthur.stock.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.stock.constant.TushareApiEnum;
import com.arthur.stock.config.TushareConfig;
import com.arthur.stock.dto.tushare.AdjFactorDTO;
import com.arthur.stock.dto.tushare.AdjFactorQueryDTO;
import com.arthur.stock.dto.tushare.BalancesheetDTO;
import com.arthur.stock.dto.tushare.BalancesheetQueryDTO;
import com.arthur.stock.dto.tushare.BlockTradeDTO;
import com.arthur.stock.dto.tushare.CashflowDTO;
import com.arthur.stock.dto.tushare.CashflowQueryDTO;
import com.arthur.stock.dto.tushare.DailyBasicDTO;
import com.arthur.stock.dto.tushare.DailyQueryDTO;
import com.arthur.stock.dto.tushare.DailyQuoteDTO;
import com.arthur.stock.dto.tushare.DividendDTO;
import com.arthur.stock.dto.tushare.DividendQueryDTO;
import com.arthur.stock.dto.tushare.ExpressDTO;
import com.arthur.stock.dto.tushare.ExpressQueryDTO;
import com.arthur.stock.dto.tushare.FinaIndicatorDTO;
import com.arthur.stock.dto.tushare.ForecastDTO;
import com.arthur.stock.dto.tushare.ForecastQueryDTO;
import com.arthur.stock.dto.tushare.HkHoldDTO;
import com.arthur.stock.dto.tushare.IncomeDTO;
import com.arthur.stock.dto.tushare.IncomeQueryDTO;
import com.arthur.stock.dto.tushare.IndexClassifyDTO;
import com.arthur.stock.dto.tushare.IndexClassifyQueryDTO;
import com.arthur.stock.dto.tushare.IndexMemberDTO;
import com.arthur.stock.dto.tushare.IndexMemberQueryDTO;
import com.arthur.stock.dto.tushare.IndexWeightDTO;
import com.arthur.stock.dto.tushare.IndexWeightQueryDTO;
import com.arthur.stock.dto.tushare.MarginDTO;
import com.arthur.stock.dto.tushare.MarginDetailDTO;
import com.arthur.stock.dto.tushare.MoneyflowDTO;
import com.arthur.stock.model.IndexDailyDO;
import com.arthur.stock.dto.tushare.NamechangeDTO;
import com.arthur.stock.dto.tushare.NamechangeQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicQueryDTO;
import com.arthur.stock.dto.tushare.StockBasicDTO;
import com.arthur.stock.dto.tushare.SuspendDDTO;
import com.arthur.stock.dto.tushare.SuspendDQueryDTO;
import com.arthur.stock.dto.tushare.StkLimitDTO;
import com.arthur.stock.dto.tushare.StkLimitQueryDTO;
import com.arthur.stock.dto.tushare.TopInstDTO;
import com.arthur.stock.dto.tushare.TopListDTO;
import com.arthur.stock.dto.tushare.TradeCalQueryDTO;
import com.arthur.stock.dto.tushare.TradeCalDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Tushare REST API 客户端，封装所有 Tushare 接口调用。
 * <p>
 * 每个方法严格对应 Tushare 原始 API，参数和返回值均为类型化的 DTO。
 *
 * @see <a href="https://tushare.pro/document/2">Tushare Pro 文档</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TushareClient {

    private final TushareConfig tushareConfig;
    private final RestTemplate restTemplate;
    private final RateLimiter rateLimiter;

    /**
     * 日线行情接口
     *
     * @param param 查询参数，ts_code / trade_date / start_date / end_date 至少传一个
     * @return 日线行情数据列表
     */
    public List<DailyQuoteDTO> daily(DailyQueryDTO param) {
        JSONObject params = buildDailyParams(param);
        return query(TushareApiEnum.DAILY, params, DailyQuoteDTO.class);
    }

    /**
     * 股票基础信息接口
     *
     * @param param 查询参数，所有字段均为可选
     * @return 股票基础信息列表
     */
    public List<StockBasicDTO> stockBasic(StockBasicQueryDTO param) {
        JSONObject params = buildStockBasicParams(param);
        return query(TushareApiEnum.STOCK_BASIC, params, StockBasicDTO.class);
    }

    /**
     * 交易日历接口
     *
     * @param param 查询参数，所有字段均为可选
     * @return 交易日历列表
     */
    public List<TradeCalDTO> tradeCal(TradeCalQueryDTO param) {
        JSONObject params = buildTradeCalParams(param);
        return query(TushareApiEnum.TRADE_CAL, params, TradeCalDTO.class);
    }

    /**
     * 复权因子接口
     *
     * @param param 查询参数，ts_code / trade_date / start_date / end_date 至少传一个
     * @return 复权因子数据列表
     */
    public List<AdjFactorDTO> adjFactor(AdjFactorQueryDTO param) {
        JSONObject params = buildAdjFactorParams(param);
        return query(TushareApiEnum.ADJ_FACTOR, params, AdjFactorDTO.class);
    }

    /**
     * 分红送股接口
     *
     * @param param 查询参数，ts_code / ann_date / record_date / ex_date / imp_ann_date 至少传一个
     * @return 分红送股数据列表
     */
    public List<DividendDTO> dividend(DividendQueryDTO param) {
        JSONObject params = buildDividendParams(param);
        return query(TushareApiEnum.DIVIDEND, params, DividendDTO.class);
    }

    /**
     * 每日基本面接口（估值/换手率/市值）。
     *
     * @param tradeDate 交易日期 yyyyMMdd；与 tsCode 至少传一个
     * @param tsCode    股票代码，可空（按交易日拉全市场时传 tradeDate 即可）
     * @return 每日基本面列表
     */
    public List<DailyBasicDTO> dailyBasic(String tradeDate, String tsCode) {
        JSONObject params = new JSONObject();
        if (tradeDate != null) {
            params.put("trade_date", tradeDate);
        }
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        return query(TushareApiEnum.DAILY_BASIC, params, DailyBasicDTO.class);
    }

    /**
     * 财务指标接口（ROE/ROA/毛利率/同比/资产负债率等）。
     *
     * @param tsCode    股票代码
     * @param startDate 起始报告期 yyyyMMdd（含）
     * @param endDate   结束报告期 yyyyMMdd（含）
     * @return 财务指标列表
     */
    public List<FinaIndicatorDTO> finaIndicator(String tsCode, String startDate, String endDate) {
        JSONObject params = new JSONObject();
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        if (startDate != null) {
            params.put("start_date", startDate);
        }
        if (endDate != null) {
            params.put("end_date", endDate);
        }
        return query(TushareApiEnum.FINA_INDICATOR, params, FinaIndicatorDTO.class);
    }

    /**
     * 利润表接口（doc_id=33）。
     *
     * @param param 查询参数，tsCode / startDate / endDate / reportType / compType 均可选
     * @return 利润表数据列表
     */
    public List<IncomeDTO> income(IncomeQueryDTO param) {
        JSONObject params = buildIncomeParams(param);
        return query(TushareApiEnum.INCOME, params, IncomeDTO.class);
    }

    /**
     * 资产负债表接口（doc_id=36）。
     *
     * @param param 查询参数，tsCode / startDate / endDate / reportType / compType 均可选
     * @return 资产负债表数据列表
     */
    public List<BalancesheetDTO> balancesheet(BalancesheetQueryDTO param) {
        JSONObject params = buildBalancesheetParams(param);
        return query(TushareApiEnum.BALANCESHEET, params, BalancesheetDTO.class);
    }

    /**
     * 现金流量表接口（doc_id=44）。
     *
     * @param param 查询参数，tsCode / startDate / endDate / reportType / compType 均可选
     * @return 现金流量表数据列表
     */
    public List<CashflowDTO> cashflow(CashflowQueryDTO param) {
        JSONObject params = buildCashflowParams(param);
        return query(TushareApiEnum.CASHFLOW, params, CashflowDTO.class);
    }

    /**
     * 业绩预告接口（doc_id=45）。
     *
     * @param param 查询参数，tsCode / annDate / startDate / endDate / period 均可选
     * @return 业绩预告数据列表
     */
    public List<ForecastDTO> forecast(ForecastQueryDTO param) {
        JSONObject params = buildForecastParams(param);
        return query(TushareApiEnum.FORECAST, params, ForecastDTO.class);
    }

    /**
     * 业绩快报接口（doc_id=46）。
     *
     * @param param 查询参数，tsCode / annDate / startDate / endDate / period 均可选
     * @return 业绩快报数据列表
     */
    public List<ExpressDTO> express(ExpressQueryDTO param) {
        JSONObject params = buildExpressParams(param);
        return query(TushareApiEnum.EXPRESS, params, ExpressDTO.class);
    }

    /**
     * 指数成分和权重接口
     *
     * @param param 查询参数，indexCode / tradeDate / startDate / endDate 至少传一个
     * @return 指数成分权重数据列表
     */
    public List<IndexWeightDTO> indexWeight(IndexWeightQueryDTO param) {
        JSONObject params = buildIndexWeightParams(param);
        return query(TushareApiEnum.INDEX_WEIGHT, params, IndexWeightDTO.class);
    }

    /**
     * 申万行业分类接口（index_classify，doc_id=181）。
     *
     * @param param 查询参数，src（如 SWS2021）必传，level 可选
     * @return 申万行业分类列表
     */
    public List<IndexClassifyDTO> indexClassify(IndexClassifyQueryDTO param) {
        JSONObject params = buildIndexClassifyParams(param);
        return query(TushareApiEnum.INDEX_CLASSIFY, params, IndexClassifyDTO.class);
    }

    /**
     * 申万行业成分股接口（index_member_all，doc_id=335），不分页。
     *
     * @param param 查询参数，tsCode / indexCode / src 均可选
     * @return 行业成分股列表
     */
    public List<IndexMemberDTO> indexMemberAll(IndexMemberQueryDTO param) {
        return indexMemberAll(param, null, null);
    }

    /**
     * 申万行业成分股接口（index_member_all，doc_id=335），支持分页。
     *
     * @param param  查询参数，tsCode / indexCode / src 均可选
     * @param offset 偏移量（null 不传）
     * @param limit  单页条数（null 不传，建议 ≤ 2000）
     * @return 行业成分股列表
     */
    public List<IndexMemberDTO> indexMemberAll(IndexMemberQueryDTO param, Integer offset, Integer limit) {
        JSONObject params = buildIndexMemberParams(param);
        if (offset != null) {
            params.put("offset", String.valueOf(offset));
        }
        if (limit != null) {
            params.put("limit", String.valueOf(limit));
        }
        return query(TushareApiEnum.INDEX_MEMBER_ALL, params, IndexMemberDTO.class);
    }

    /**
     * 股票更名历史接口（namechange，doc_id=160），支持分页。
     *
     * @param param  查询参数 tsCode/startDate/endDate 均可选
     * @param offset 偏移量（null 不传）
     * @param limit  单页条数（null 不传，建议 ≤ 5000）
     * @return 更名记录列表
     */
    public List<NamechangeDTO> namechange(NamechangeQueryDTO param, Integer offset, Integer limit) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (offset != null) {
            params.put("offset", String.valueOf(offset));
        }
        if (limit != null) {
            params.put("limit", String.valueOf(limit));
        }
        return query(TushareApiEnum.NAMECHANGE, params, NamechangeDTO.class);
    }

    /**
     * 股票停复牌信息（suspend_d，doc_id=161）。
     * <p>
     * 单次最大 10000 行，需分页。
     *
     * @param param  查询参数 tsCode/startDate/endDate 均可选
     * @param offset 偏移量（null 不传）
     * @param limit  单页条数（null 不传，建议 ≤ 10000）
     * @return 停牌记录列表
     */
    public List<SuspendDDTO> suspendD(SuspendDQueryDTO param, Integer offset, Integer limit) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (offset != null) {
            params.put("offset", String.valueOf(offset));
        }
        if (limit != null) {
            params.put("limit", String.valueOf(limit));
        }
        return query(TushareApiEnum.SUSPEND_D, params, SuspendDDTO.class);
    }

    /**
     * 涨跌停价（stk_limit，doc_id=183）。
     * <p>
     * 接口单次不限行数，但建议分页/限制（本客户端按 PAGE_SIZE 分页调用）。
     *
     * @param param  查询参数 tsCode/startDate/endDate 均可选
     * @param offset 偏移量（null 不传）
     * @param limit  单页条数（null 不传）
     * @return 涨跌停价记录列表
     */
    public List<StkLimitDTO> stkLimit(StkLimitQueryDTO param, Integer offset, Integer limit) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (offset != null) {
            params.put("offset", String.valueOf(offset));
        }
        if (limit != null) {
            params.put("limit", String.valueOf(limit));
        }
        return query(TushareApiEnum.STK_LIMIT, params, StkLimitDTO.class);
    }

    /**
     * 指数日线行情接口（index_daily）。
     * <p>
     * 直接解析为 {@link IndexDailyDO}（字段一一对应，DO 上带 {@code @JSONField} 映射）。
     *
     * @param tsCode    指数代码（如 000001.SH）
     * @param startDate 起始交易日 yyyyMMdd（可空）
     * @param endDate   结束交易日 yyyyMMdd（可空）
     * @return 指数日线行情列表
     */
    public List<IndexDailyDO> fetchIndexDaily(String tsCode, String startDate, String endDate) {
        JSONObject params = new JSONObject();
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        if (startDate != null) {
            params.put("start_date", startDate);
        }
        if (endDate != null) {
            params.put("end_date", endDate);
        }
        return query(TushareApiEnum.INDEX_DAILY, params, IndexDailyDO.class);
    }

    /**
     * 个股资金流向接口（moneyflow）。
     *
     * @param tradeDate 交易日期 yyyyMMdd
     * @param tsCode    股票代码
     * @return 个股资金流向列表
     */
    public List<MoneyflowDTO> moneyflow(String tradeDate, String tsCode) {
        JSONObject params = new JSONObject();
        if (tradeDate != null) {
            params.put("trade_date", tradeDate);
        }
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        return query(TushareApiEnum.MONEYFLOW, params, MoneyflowDTO.class);
    }

    /**
     * 沪深港通持股明细接口（hk_hold）。
     *
     * @param tradeDate 交易日期 yyyyMMdd
     * @param tsCode    股票代码
     * @return 沪深港通持股明细列表
     */
    public List<HkHoldDTO> hkHold(String tradeDate, String tsCode) {
        JSONObject params = new JSONObject();
        if (tradeDate != null) {
            params.put("trade_date", tradeDate);
        }
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        return query(TushareApiEnum.HK_HOLD, params, HkHoldDTO.class);
    }

    /**
     * 龙虎榜个股明细接口（top_list）。
     *
     * @param tradeDate 交易日期 yyyyMMdd
     * @param tsCode    股票代码
     * @return 龙虎榜个股明细列表
     */
    public List<TopListDTO> topList(String tradeDate, String tsCode) {
        JSONObject params = new JSONObject();
        if (tradeDate != null) {
            params.put("trade_date", tradeDate);
        }
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        return query(TushareApiEnum.TOP_LIST, params, TopListDTO.class);
    }

    /**
     * 龙虎榜营业部席位明细接口（top_inst）。
     *
     * @param tradeDate 交易日期 yyyyMMdd
     * @param tsCode    股票代码
     * @return 龙虎榜营业部席位明细列表
     */
    public List<TopInstDTO> topInst(String tradeDate, String tsCode) {
        JSONObject params = new JSONObject();
        if (tradeDate != null) {
            params.put("trade_date", tradeDate);
        }
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        return query(TushareApiEnum.TOP_INST, params, TopInstDTO.class);
    }

    /**
     * 大宗交易接口（block_trade）。
     *
     * @param tradeDate 交易日期 yyyyMMdd
     * @param tsCode    股票代码
     * @return 大宗交易列表
     */
    public List<BlockTradeDTO> blockTrade(String tradeDate, String tsCode) {
        JSONObject params = new JSONObject();
        if (tradeDate != null) {
            params.put("trade_date", tradeDate);
        }
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        return query(TushareApiEnum.BLOCK_TRADE, params, BlockTradeDTO.class);
    }

    /**
     * 融资融券汇总接口（margin）。
     *
     * @param tradeDate  交易日期 yyyyMMdd
     * @param exchangeId 交易所代码（SSE/SZSE）
     * @return 融资融券汇总列表
     */
    public List<MarginDTO> margin(String tradeDate, String exchangeId) {
        JSONObject params = new JSONObject();
        if (tradeDate != null) {
            params.put("trade_date", tradeDate);
        }
        if (exchangeId != null) {
            params.put("exchange_id", exchangeId);
        }
        return query(TushareApiEnum.MARGIN, params, MarginDTO.class);
    }

    /**
     * 融资融券个股明细接口（margin_detail）。
     *
     * @param tradeDate 交易日期 yyyyMMdd
     * @param tsCode    股票代码
     * @return 融资融券个股明细列表
     */
    public List<MarginDetailDTO> marginDetail(String tradeDate, String tsCode) {
        JSONObject params = new JSONObject();
        if (tradeDate != null) {
            params.put("trade_date", tradeDate);
        }
        if (tsCode != null) {
            params.put("ts_code", tsCode);
        }
        return query(TushareApiEnum.MARGIN_DETAIL, params, MarginDetailDTO.class);
    }

    // ==================== 通用请求方法 ====================

    private <T> List<T> query(TushareApiEnum api, JSONObject params, Class<T> clazz) {
        rateLimiter.acquire(api.getApiName());

        JSONObject body = new JSONObject();
        body.put("api_name", api.getApiName());
        body.put("token", tushareConfig.getToken());
        body.put("params", params);
        body.put("fields", api.getFields());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                tushareConfig.getBaseUrl(), HttpMethod.POST, entity, String.class);

        return parseResponse(response.getBody(), clazz);
    }

    /**
     * 解析 Tushare 响应，将 fields + items 转换为 DTO 列表
     */
    private <T> List<T> parseResponse(String responseBody, Class<T> clazz) {
        JSONObject result = JSON.parseObject(responseBody);
        int code = result.getIntValue("code");
        if (code != 0) {
            String msg = result.getString("msg");
            throw new RuntimeException("Tushare API error: api error, code=" + code + ", msg=" + msg);
        }

        JSONObject data = result.getJSONObject("data");
        if (data == null) {
            return Collections.emptyList();
        }

        JSONArray fieldArray = data.getJSONArray("fields");
        JSONArray itemsArray = data.getJSONArray("items");
        if (fieldArray == null || itemsArray == null || itemsArray.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> fields = fieldArray.toJavaList(String.class);
        List<T> list = new ArrayList<>(itemsArray.size());
        for (int i = 0; i < itemsArray.size(); i++) {
            JSONArray item = itemsArray.getJSONArray(i);
            JSONObject row = new JSONObject(fields.size());
            for (int j = 0; j < fields.size(); j++) {
                row.put(fields.get(j), item.get(j));
            }
            list.add(row.toJavaObject(clazz));
        }
        return list;
    }

    /**
     * 构建 daily 接口参数，非空字段才传入
     */
    private JSONObject buildDailyParams(DailyQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getTradeDate() != null) {
            params.put("trade_date", param.getTradeDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (param.getOffset() != null) {
            params.put("offset", String.valueOf(param.getOffset()));
        }
        if (param.getLimit() != null) {
            params.put("limit", String.valueOf(param.getLimit()));
        }
        return params;
    }

    /**
     * 构建 stock_basic 接口参数，非空字段才传入
     */
    private JSONObject buildStockBasicParams(StockBasicQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getName() != null) {
            params.put("name", param.getName());
        }
        if (param.getMarket() != null) {
            params.put("market", param.getMarket());
        }
        if (param.getListStatus() != null) {
            params.put("list_status", param.getListStatus());
        }
        if (param.getExchange() != null) {
            params.put("exchange", param.getExchange());
        }
        if (param.getIsHs() != null) {
            params.put("is_hs", param.getIsHs());
        }
        return params;
    }

    /**
     * 构建 trade_cal 接口参数，非空字段才传入
     */
    private JSONObject buildTradeCalParams(TradeCalQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getExchange() != null) {
            params.put("exchange", param.getExchange());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (param.getIsOpen() != null) {
            params.put("is_open", param.getIsOpen());
        }
        return params;
    }

    /**
     * 构建 adj_factor 接口参数，非空字段才传入
     */
    private JSONObject buildAdjFactorParams(AdjFactorQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getTradeDate() != null) {
            params.put("trade_date", param.getTradeDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        return params;
    }

    /**
     * 构建 dividend 接口参数，非空字段才传入
     */
    private JSONObject buildDividendParams(DividendQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getAnnDate() != null) {
            params.put("ann_date", param.getAnnDate());
        }
        if (param.getRecordDate() != null) {
            params.put("record_date", param.getRecordDate());
        }
        if (param.getExDate() != null) {
            params.put("ex_date", param.getExDate());
        }
        if (param.getImpAnnDate() != null) {
            params.put("imp_ann_date", param.getImpAnnDate());
        }
        return params;
    }

    /**
     * 构建 index_weight 接口参数，非空字段才传入
     */
    private JSONObject buildIndexWeightParams(IndexWeightQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getIndexCode() != null) {
            params.put("ts_code", param.getIndexCode());
        }
        if (param.getTradeDate() != null) {
            params.put("trade_date", param.getTradeDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        return params;
    }

    /**
     * 构建 index_classify 接口参数，非空字段才传入（src 如 SWS2021）
     */
    private JSONObject buildIndexClassifyParams(IndexClassifyQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getSrc() != null) {
            params.put("src", param.getSrc());
        }
        if (param.getLevel() != null) {
            params.put("level", param.getLevel());
        }
        return params;
    }

    /**
     * 构建 index_member_all 接口参数，非空字段才传入
     */
    private JSONObject buildIndexMemberParams(IndexMemberQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getIndexCode() != null) {
            params.put("index_code", param.getIndexCode());
        }
        if (param.getSrc() != null) {
            params.put("src", param.getSrc());
        }
        return params;
    }

    /**
     * 构建 income 接口参数，非空字段才传入
     */
    private JSONObject buildIncomeParams(IncomeQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getAnnDate() != null) {
            params.put("ann_date", param.getAnnDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (param.getReportType() != null) {
            params.put("report_type", param.getReportType());
        }
        if (param.getCompType() != null) {
            params.put("comp_type", param.getCompType());
        }
        return params;
    }

    /**
     * 构建 balancesheet 接口参数，非空字段才传入
     */
    private JSONObject buildBalancesheetParams(BalancesheetQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getAnnDate() != null) {
            params.put("ann_date", param.getAnnDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (param.getReportType() != null) {
            params.put("report_type", param.getReportType());
        }
        if (param.getCompType() != null) {
            params.put("comp_type", param.getCompType());
        }
        return params;
    }

    /**
     * 构建 cashflow 接口参数，非空字段才传入
     */
    private JSONObject buildCashflowParams(CashflowQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getAnnDate() != null) {
            params.put("ann_date", param.getAnnDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (param.getReportType() != null) {
            params.put("report_type", param.getReportType());
        }
        if (param.getCompType() != null) {
            params.put("comp_type", param.getCompType());
        }
        return params;
    }

    /**
     * 构建 forecast 接口参数，非空字段才传入
     */
    private JSONObject buildForecastParams(ForecastQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getAnnDate() != null) {
            params.put("ann_date", param.getAnnDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (param.getPeriod() != null) {
            params.put("period", param.getPeriod());
        }
        return params;
    }

    /**
     * 构建 express 接口参数，非空字段才传入
     */
    private JSONObject buildExpressParams(ExpressQueryDTO param) {
        JSONObject params = new JSONObject();
        if (param.getTsCode() != null) {
            params.put("ts_code", param.getTsCode());
        }
        if (param.getAnnDate() != null) {
            params.put("ann_date", param.getAnnDate());
        }
        if (param.getStartDate() != null) {
            params.put("start_date", param.getStartDate());
        }
        if (param.getEndDate() != null) {
            params.put("end_date", param.getEndDate());
        }
        if (param.getPeriod() != null) {
            params.put("period", param.getPeriod());
        }
        return params;
    }
}
