package com.example.tradingagent.controllers;

import com.example.tradingagent.TinkoffApi;
import com.example.tradingagent.TinkoffApiUtils;
import com.example.tradingagent.dto.TradeRequest;
import com.example.tradingagent.entities.TradeDecision;
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

    private static final BigDecimal MIN_TURNOVER_RUB = new BigDecimal("500000");
    // Фильтр "Пилы" (Choppy Market): ADX < 25 запрещает вход по трендовым стратегиям
    private static final double MIN_ADX_THRESHOLD = 25.0;
    private final TinkoffMarketDataService marketDataService;
    private final TechnicalIndicatorService indicatorService;
    private final TinkoffOrderService orderService;
    private final TinkoffInstrumentsService instrumentsService;
    private final NewsService newsService;
    private final TinkoffAccountService accountService;
    private final AuditService auditService;
    private final TradingStateMachine stateMachine;
    private final InvestApi api;

    @Autowired
    public MarketDataController(TinkoffMarketDataService marketDataService,
                                TechnicalIndicatorService indicatorService,
                                TinkoffOrderService orderService,
                                TinkoffInstrumentsService instrumentsService,
                                NewsService newsService,
                                TinkoffAccountService accountService,
                                AuditService auditService,
                                TradingStateMachine stateMachine) {
        this.marketDataService = marketDataService;
        this.indicatorService = indicatorService;
        this.orderService = orderService;
        this.instrumentsService = instrumentsService;
        this.newsService = newsService;
        this.accountService = accountService;
        this.auditService = auditService;
        this.stateMachine = stateMachine;
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
        int daysToRequestM15 = 1;

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

                if (indicatorsH1.isEmpty() || indicatorsM15.isEmpty()) {
                    // ЛОГИРОВАНИЕ: Недостаточно данных
                    HistoricCandle lastCandle = candlesH1.isEmpty() ? candlesM15.getLast() : candlesH1.getLast();
                    BigDecimal priceClose = TinkoffApiUtils.quotationToBigDecimal(lastCandle.getClose());
                    com.example.tradingagent.entities.MarketSnapshot snapshot = auditService.saveMarketSnapshot(
                        share.getTicker(), indicatorsH1.isEmpty() ? indicatorsM15 : indicatorsH1, priceClose);
                    if (snapshot != null) {
                        auditService.saveTradeDecision(snapshot,
                            com.example.tradingagent.entities.TradeDecision.DecisionType.IGNORE,
                            "INSUFFICIENT_DATA",
                            "Недостаточно индикаторов для анализа");
                    }
                    continue;
                }

                // ЛОГИРОВАНИЕ В БД: Сохраняем снимок рынка
                HistoricCandle lastCandle = candlesH1.getLast();
                BigDecimal priceClose = TinkoffApiUtils.quotationToBigDecimal(lastCandle.getClose());
                com.example.tradingagent.entities.MarketSnapshot snapshot = auditService.saveMarketSnapshot(
                    share.getTicker(), indicatorsH1, priceClose);

                // --- ФИЛЬТР ADX (Только для новых входов) ---
                if (!hasPosition) {
                    double adx = indicatorsH1.getOrDefault("adx_14", 0.0);
                    if (adx < MIN_ADX_THRESHOLD) {
                        skippedAdx++;
                        // ЛОГИРОВАНИЕ РЕШЕНИЯ: Почему не торгуем
                        if (snapshot != null) {
                            auditService.saveTradeDecision(snapshot, 
                                com.example.tradingagent.entities.TradeDecision.DecisionType.IGNORE,
                                "FILTERED_BY_ADX",
                                String.format("ADX=%.1f < %.1f (слабый тренд, флет)", adx, MIN_ADX_THRESHOLD));
                        }
                        continue;
                    }
                }

                // ЛОГИРОВАНИЕ РЕШЕНИЯ: Если есть позиция - HOLD, иначе - потенциальный BUY сигнал
                if (snapshot != null && hasPosition) {
                    auditService.saveTradeDecision(snapshot,
                        com.example.tradingagent.entities.TradeDecision.DecisionType.HOLD,
                        "POSITION_ACTIVE",
                        "Позиция уже открыта, мониторим SL/TP");
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

            Instant effectiveTimeTo = to.orElse(Instant.now());
            List<HistoricCandle> candles = marketDataService.getHistoricCandles(tradeRequest.getInstrumentFigi(), 7, CandleInterval.CANDLE_INTERVAL_15_MIN, effectiveTimeTo);

            if (candles.isEmpty()) return ResponseEntity.badRequest().body("Нет данных");

            // Вызов индикаторов обновлен
            Map<String, Double> indicators = indicatorService.calculateIndicators(candles, tradeRequest.getTicker(), tradeRequest.getInstrumentFigi());

            if (indicators.isEmpty()) {
                return ResponseEntity.badRequest().body("Не удалось рассчитать индикаторы");
            }

            // ЛОГИРОВАНИЕ: Сохраняем снимок рынка перед выполнением сделки
            HistoricCandle lastCandle = candles.getLast();
            BigDecimal priceClose = TinkoffApiUtils.quotationToBigDecimal(lastCandle.getClose());
            com.example.tradingagent.entities.MarketSnapshot snapshot = auditService.saveMarketSnapshot(
                tradeRequest.getTicker(), indicators, priceClose);

            // ЛОГИРОВАНИЕ РЕШЕНИЯ: Решение n8n
            if (snapshot != null) {
                TradeDecision.DecisionType decisionType = "BUY".equalsIgnoreCase(tradeRequest.getAction()) 
                    ? TradeDecision.DecisionType.BUY 
                    : "SELL".equalsIgnoreCase(tradeRequest.getAction()) 
                        ? TradeDecision.DecisionType.SELL 
                        : TradeDecision.DecisionType.HOLD;
                
                String reasonCode = tradeRequest.getReason() != null 
                    ? tradeRequest.getReason().substring(0, Math.min(100, tradeRequest.getReason().length()))
                    : "N8N_SIGNAL";
                String reasonDetails = String.format("Score: %.2f, Reason: %s", 
                    tradeRequest.getConfidenceScore(), 
                    tradeRequest.getReason() != null ? tradeRequest.getReason() : "No reason provided");
                
                auditService.saveTradeDecision(snapshot, decisionType, reasonCode, reasonDetails);
            }

            // Проверка State Machine: можно ли торговать?
            if (!stateMachine.canTrade(tradeRequest.getInstrumentFigi())) {
                String state = stateMachine.getState(tradeRequest.getInstrumentFigi()).toString();
                log("BLOCK: Инструмент " + tradeRequest.getTicker() + " в состоянии " + state);
                return ResponseEntity.badRequest().body("Торговля заблокирована: инструмент в состоянии " + state);
            }

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