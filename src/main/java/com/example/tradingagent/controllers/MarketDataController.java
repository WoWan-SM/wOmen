package com.example.tradingagent.controllers;

import com.example.tradingagent.TinkoffApi;
import com.example.tradingagent.TinkoffApiUtils;
import com.example.tradingagent.dto.TradeRequest;
import com.example.tradingagent.services.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.GetOrderBookResponse;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;
import ru.tinkoff.piapi.contract.v1.Instrument;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.core.InvestApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class MarketDataController {

    private final TinkoffMarketDataService marketDataService;
    private final TechnicalIndicatorService indicatorService;
    private final TinkoffOrderService orderService;
    private final TinkoffInstrumentsService instrumentsService;
    private final NewsService newsService;
    private final RiskManagementService riskManagementService;
    private final InvestApi api;

    private static final BigDecimal MIN_TURNOVER_RUB = new BigDecimal("5000000");
    private static final double MIN_ADX_THRESHOLD = 20.0;

    // Circuit Breaker
    private BigDecimal dailyLoss = BigDecimal.ZERO;
    private static final BigDecimal DAILY_LOSS_LIMIT = new BigDecimal("3000.00");

    private void log(String msg) {
        System.out.println("[CONTROLLER " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + msg);
    }

    @Autowired
    public MarketDataController(TinkoffMarketDataService marketDataService,
                                TechnicalIndicatorService indicatorService,
                                TinkoffOrderService orderService,
                                TinkoffInstrumentsService instrumentsService,
                                NewsService newsService,
                                RiskManagementService riskManagementService) {
        this.marketDataService = marketDataService;
        this.indicatorService = indicatorService;
        this.orderService = orderService;
        this.instrumentsService = instrumentsService;
        this.newsService = newsService;
        this.riskManagementService = riskManagementService;
        this.api = TinkoffApi.getApi();
    }

    @GetMapping("/scan-market")
    public ResponseEntity<?> scanMarket(
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to) {

        log("Запуск сканирования рынка...");
        Instant effectiveTimeTo = to.orElse(Instant.now());
        List<Share> shares = instrumentsService.getBlueChips();
        List<Map<String, Object>> marketData = new ArrayList<>();

        int daysToRequestH1 = 30;
        int daysToRequestM15 = 7;

        for (Share share : shares) {
            try {
                // 1. Фильтр оборота
                List<HistoricCandle> candlesH1 = marketDataService.getHistoricCandles(share.getFigi(), daysToRequestH1, CandleInterval.CANDLE_INTERVAL_HOUR, effectiveTimeTo);
                if (candlesH1.isEmpty()) continue;

                HistoricCandle lastH1 = candlesH1.get(candlesH1.size() - 1);
                BigDecimal closeH1 = TinkoffApiUtils.quotationToBigDecimal(lastH1.getClose());
                BigDecimal volumeH1 = BigDecimal.valueOf(lastH1.getVolume());
                BigDecimal turnover = closeH1.multiply(volumeH1);

                if (turnover.compareTo(MIN_TURNOVER_RUB) < 0) {
                    continue;
                }

                // 2. Расчет индикаторов
                List<HistoricCandle> candlesM15 = marketDataService.getHistoricCandles(share.getFigi(), daysToRequestM15, CandleInterval.CANDLE_INTERVAL_15_MIN, effectiveTimeTo);
                if (candlesM15.isEmpty()) continue;

                Map<String, Double> indicatorsH1 = indicatorService.calculateIndicators(candlesH1);
                Map<String, Double> indicatorsM15 = indicatorService.calculateIndicators(candlesM15);

                if (indicatorsH1.isEmpty() || indicatorsM15.isEmpty()) continue;

                // 3. Предварительный фильтр ADX (чтобы не спамить n8n мусором)
                double adx = indicatorsH1.getOrDefault("adx_14", 0.0);
                // Логируем только интересные ситуации, чтобы не забивать консоль
                if (adx > 25) {
                     log("Кандидат " + share.getTicker() + ": ADX=" + String.format("%.1f", adx) + ", MACD_Hist=" + String.format("%.2f", indicatorsM15.get("macd_hist")));
                }

                Map<String, Object> instrumentData = new HashMap<>();
                instrumentData.put("instrument_figi", share.getFigi());
                instrumentData.put("ticker", share.getTicker());
                instrumentData.put("indicators_h1", indicatorsH1);
                instrumentData.put("indicators_m15", indicatorsM15);

                if (to.isEmpty()) {
                    GetOrderBookResponse orderBookResponse = marketDataService.getOrderBook(share.getFigi());
                    Map<String, Object> serializableOrderBook = new HashMap<>();
                    serializableOrderBook.put("bids_volume", orderBookResponse.getBidsList().stream().mapToLong(o -> o.getQuantity()).sum());
                    serializableOrderBook.put("asks_volume", orderBookResponse.getAsksList().stream().mapToLong(o -> o.getQuantity()).sum());
                    instrumentData.put("order_book", serializableOrderBook);

                    List<String> tickerNews = newsService.getRecentNewsHeadlines(share.getTicker());
                    instrumentData.put("ticker_news", tickerNews);
                }

                marketData.add(instrumentData);
            } catch (Exception e) {
                System.err.println("Ошибка при анализе " + share.getTicker() + ": " + e.getMessage());
            }
        }
        log("Сканирование завершено. Найдено " + marketData.size() + " инструментов для анализа в n8n.");
        return ResponseEntity.ok(marketData);
    }

    @PostMapping("/execute-trade")
    public ResponseEntity<?> executeTrade(@RequestBody TradeRequest tradeRequest,
                                          @RequestParam(name = "simulate", defaultValue = "false") boolean simulate,
                                          @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to) {
        try {
            log(">>> ВХОДЯЩИЙ ЗАПРОС ОТ n8n: " + tradeRequest.getAction() + " по " + tradeRequest.getTicker() + " (Score: " + tradeRequest.getConfidenceScore() + ")");
            log("Причина: " + tradeRequest.getReason());

            if (dailyLoss.compareTo(DAILY_LOSS_LIMIT) >= 0) {
                log("ОТКАЗ: Превышен дневной лимит убытка.");
                return ResponseEntity.badRequest().body("Торговля остановлена: превышен дневной лимит убытка.");
            }

            Instant effectiveTimeTo = to.orElse(Instant.now());
            List<HistoricCandle> candles = marketDataService.getHistoricCandles(tradeRequest.getInstrumentFigi(), 7, CandleInterval.CANDLE_INTERVAL_15_MIN, effectiveTimeTo);

            if (candles.isEmpty()) return ResponseEntity.badRequest().body("Нет данных");

            Map<String, Double> indicators = indicatorService.calculateIndicators(candles);

            // Фильтр ADX - проверка "на входе"
            double adx = indicators.getOrDefault("adx_14", 0.0);
            if (adx < MIN_ADX_THRESHOLD) {
                log("ОТКАЗ: ADX упал (" + adx + ") пока сигнал шел от n8n.");
                return ResponseEntity.ok().body("Сигнал пропущен: ADX " + adx + " < " + MIN_ADX_THRESHOLD);
            }

            if (simulate) {
                // Симуляция отключена для краткости в этом примере
                return ResponseEntity.ok().body("Simulation mode");
            } else {
                orderService.executeTrade(tradeRequest, indicators);
                return ResponseEntity.ok().body("Ордер отправлен в обработку.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Ошибка: " + e.getMessage());
        }
    }
}