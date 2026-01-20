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
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Portfolio;
import ru.tinkoff.piapi.core.models.Position;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
public class MarketDataController {

    private static final BigDecimal MIN_TURNOVER_RUB = new BigDecimal("5000000");
    private static final double MIN_ADX_THRESHOLD = 25.0;
    private static final BigDecimal DAILY_LOSS_LIMIT = new BigDecimal("3000.00");
    private final TinkoffMarketDataService marketDataService;
    private final TechnicalIndicatorService indicatorService;
    private final TinkoffOrderService orderService;
    private final TinkoffInstrumentsService instrumentsService;
    private final NewsService newsService;
    private final TinkoffAccountService accountService;
    private final InvestApi api;
    // Circuit Breaker
    private final BigDecimal dailyLoss = BigDecimal.ZERO;

    @Autowired
    public MarketDataController(TinkoffMarketDataService marketDataService,
                                TechnicalIndicatorService indicatorService,
                                TinkoffOrderService orderService,
                                TinkoffInstrumentsService instrumentsService,
                                NewsService newsService,
                                TinkoffAccountService accountService) {
        this.marketDataService = marketDataService;
        this.indicatorService = indicatorService;
        this.orderService = orderService;
        this.instrumentsService = instrumentsService;
        this.newsService = newsService;
        this.accountService = accountService;
        this.api = TinkoffApi.getApi();
    }

    private void log(String msg) {
        System.out.println("[SCANNER " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + msg);
    }

    @GetMapping("/scan-market")
    public ResponseEntity<?> scanMarket(
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to) {

        log("--- СТАРТ СКАНИРОВАНИЯ ---");
        Instant effectiveTimeTo = to.orElse(Instant.now());
        List<Share> shares = instrumentsService.getBlueChips();
        List<Map<String, Object>> marketData = new ArrayList<>();

        // 1. Получаем список FIGI, которые уже есть в портфеле (чтобы их не скипать)
        Set<String> portfolioFigis = new HashSet<>();
        try {
            Portfolio portfolio = api.getOperationsService().getPortfolioSync(accountService.getSandboxAccountId());
            for (Position p : portfolio.getPositions()) {
                if (p.getQuantity().compareTo(BigDecimal.ZERO) != 0) {
                    portfolioFigis.add(p.getFigi());
                }
            }
            if (!portfolioFigis.isEmpty()) {
                log("Активные позиции в портфеле: " + portfolioFigis.size() + " шт. Проверка вне очереди.");
            }
        } catch (Exception e) {
            System.err.println("Ошибка получения портфеля: " + e.getMessage());
        }

        int daysToRequestH1 = 30;
        int daysToRequestM15 = 7;

        int skippedTurnover = 0;
        int skippedAdx = 0;

        for (Share share : shares) {
            try {
                boolean hasPosition = portfolioFigis.contains(share.getFigi());

                // 2. Получаем данные
                List<HistoricCandle> candlesH1 = marketDataService.getHistoricCandles(share.getFigi(), daysToRequestH1, CandleInterval.CANDLE_INTERVAL_HOUR, effectiveTimeTo);
                if (candlesH1.isEmpty()) continue;

                // --- ФИЛЬТРЫ (Применяем, только если НЕТ позиции) ---
                if (!hasPosition) {
                    HistoricCandle lastH1 = candlesH1.getLast();
                    BigDecimal closeH1 = TinkoffApiUtils.quotationToBigDecimal(lastH1.getClose());
                    BigDecimal volumeH1 = BigDecimal.valueOf(lastH1.getVolume());
                    BigDecimal turnover = closeH1.multiply(volumeH1);

                    if (turnover.compareTo(MIN_TURNOVER_RUB) < 0) {
                        skippedTurnover++;
                        continue;
                    }
                }

                List<HistoricCandle> candlesM15 = marketDataService.getHistoricCandles(share.getFigi(), daysToRequestM15, CandleInterval.CANDLE_INTERVAL_15_MIN, effectiveTimeTo);
                if (candlesM15.isEmpty()) continue;

                // Передаем ticker и figi для логирования
                Map<String, Double> indicatorsH1 = indicatorService.calculateIndicators(candlesH1, share.getTicker(), share.getFigi());
                Map<String, Double> indicatorsM15 = indicatorService.calculateIndicators(candlesM15, share.getTicker(), share.getFigi());

                if (indicatorsH1.isEmpty() || indicatorsM15.isEmpty()) continue;

                // --- ФИЛЬТР ADX (Только для новых входов) ---
                if (!hasPosition) {
                    double adx = indicatorsH1.getOrDefault("adx_14", 0.0);
                    if (adx < MIN_ADX_THRESHOLD) {
                        skippedAdx++;
                        continue;
                    }
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
        log(String.format("Итоги: %d тикеров -> n8n. Скип: %d (объем), %d (ADX).", marketData.size(), skippedTurnover, skippedAdx));
        return ResponseEntity.ok(marketData);
    }

    @PostMapping("/execute-trade")
    public ResponseEntity<?> executeTrade(@RequestBody TradeRequest tradeRequest,
                                          @RequestParam(name = "simulate", defaultValue = "false") boolean simulate,
                                          @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to) {
        try {
            log("<<< СИГНАЛ n8n: " + tradeRequest.getAction() + " | " + tradeRequest.getTicker() +
                    " | Score: " + tradeRequest.getConfidenceScore());

            if (tradeRequest.getReason() != null) {
                 log("Reason: " + tradeRequest.getReason());
            }

            if (dailyLoss.compareTo(DAILY_LOSS_LIMIT) >= 0) {
                log("BLOCK: Дневной лимит убытка превышен!");
                return ResponseEntity.badRequest().body("Торговля остановлена: превышен дневной лимит убытка.");
            }

            Instant effectiveTimeTo = to.orElse(Instant.now());
            List<HistoricCandle> candles = marketDataService.getHistoricCandles(tradeRequest.getInstrumentFigi(), 7, CandleInterval.CANDLE_INTERVAL_15_MIN, effectiveTimeTo);

            if (candles.isEmpty()) return ResponseEntity.badRequest().body("Нет данных");

            // Вызов индикаторов обновлен
            Map<String, Double> indicators = indicatorService.calculateIndicators(candles, tradeRequest.getTicker(), tradeRequest.getInstrumentFigi());

            if (simulate) {
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