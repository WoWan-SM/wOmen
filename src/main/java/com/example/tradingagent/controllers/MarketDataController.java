package com.example.tradingagent.controllers;

import com.example.tradingagent.TinkoffApi;
import com.example.tradingagent.TinkoffApiUtils;
import com.example.tradingagent.dto.TradeRequest;
import com.example.tradingagent.services.NewsService;
import com.example.tradingagent.services.TechnicalIndicatorService;
import com.example.tradingagent.services.TinkoffInstrumentsService;
import com.example.tradingagent.services.TinkoffMarketDataService;
import com.example.tradingagent.services.TinkoffOrderService;
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
    private final InvestApi api;

    // --- Внутренний класс для хранения информации об открытой позиции в симуляции ---
    private static class SimulatedPosition {
        String figi;
        long lots;
        BigDecimal entryPrice;
        BigDecimal stopLossPrice;
        BigDecimal takeProfitPrice;
        int lotSize;
        PositionType type;

        SimulatedPosition(String figi, long lots, BigDecimal entryPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice, int lotSize, PositionType type) {
            this.figi = figi;
            this.lots = lots;
            this.entryPrice = entryPrice;
            this.stopLossPrice = stopLossPrice;
            this.takeProfitPrice = takeProfitPrice;
            this.lotSize = lotSize;
            this.type = type;
        }
    }
    private enum PositionType { LONG, SHORT }
    private BigDecimal simulatedCash = new BigDecimal("10000.00");
    private final List<SimulatedPosition> openPositions = new ArrayList<>();
    private final BigDecimal commissionRate = new BigDecimal("0.00004");
    private static final BigDecimal ATR_MULTIPLIER_STOP_LOSS = new BigDecimal("2.5");
    private static final BigDecimal ATR_MULTIPLIER_TAKE_PROFIT = new BigDecimal("5.0");


    @Autowired
    public MarketDataController(TinkoffMarketDataService marketDataService,
                                TechnicalIndicatorService indicatorService,
                                TinkoffOrderService orderService,
                                TinkoffInstrumentsService instrumentsService,
                                NewsService newsService) {
        this.marketDataService = marketDataService;
        this.indicatorService = indicatorService;
        this.orderService = orderService;
        this.instrumentsService = instrumentsService;
        this.newsService = newsService;
        this.api = TinkoffApi.getApi();
    }

    @GetMapping("/scan-market")
    public ResponseEntity<?> scanMarket(
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to) {

        Instant effectiveTimeTo = to.orElse(Instant.now());
        List<Share> shares = instrumentsService.getBlueChips();
        List<Map<String, Object>> marketData = new ArrayList<>();

        int daysToRequestH1 = 30;
        int daysToRequestM15 = 7;

        // Временно отключаем получение общих новостей, так как Tinkoff API его не поддерживает напрямую
        // List<String> generalNews = newsService.getGeneralRussianNews();

        if (to.isPresent() && !openPositions.isEmpty()) {
            new ArrayList<>(openPositions).forEach(position -> {
                List<HistoricCandle> positionCandles = marketDataService.getHistoricCandles(position.figi, daysToRequestM15, CandleInterval.CANDLE_INTERVAL_15_MIN, effectiveTimeTo);
                if (!positionCandles.isEmpty()) {
                    checkAndCloseSimulatedPosition(position, positionCandles.get(positionCandles.size() - 1));
                }
            });
        }

        for (Share share : shares) {
            try {
                List<HistoricCandle> candlesH1 = marketDataService.getHistoricCandles(share.getFigi(), daysToRequestH1, CandleInterval.CANDLE_INTERVAL_HOUR, effectiveTimeTo);
                List<HistoricCandle> candlesM15 = marketDataService.getHistoricCandles(share.getFigi(), daysToRequestM15, CandleInterval.CANDLE_INTERVAL_15_MIN, effectiveTimeTo);

                if (candlesH1.isEmpty() || candlesM15.isEmpty()) continue;

                Map<String, Double> indicatorsH1 = indicatorService.calculateIndicators(candlesH1);
                Map<String, Double> indicatorsM15 = indicatorService.calculateIndicators(candlesM15);
                if (indicatorsH1.isEmpty() || indicatorsM15.isEmpty()) continue;

                Map<String, Object> instrumentData = new HashMap<>();
                instrumentData.put("instrument_figi", share.getFigi());
                instrumentData.put("ticker", share.getTicker());
                instrumentData.put("indicators_h1", indicatorsH1);
                instrumentData.put("indicators_m15", indicatorsM15);

                // instrumentData.put("general_news", generalNews);

                if (to.isEmpty()) {
                    GetOrderBookResponse orderBookResponse = marketDataService.getOrderBook(share.getFigi());
                    Map<String, Object> serializableOrderBook = new HashMap<>();
                    serializableOrderBook.put("bids_volume", orderBookResponse.getBidsList().stream().mapToLong(o -> o.getQuantity()).sum());
                    serializableOrderBook.put("asks_volume", orderBookResponse.getAsksList().stream().mapToLong(o -> o.getQuantity()).sum());
                    instrumentData.put("order_book", serializableOrderBook);

                    // Используем FIGI для получения новостей через Tinkoff API
                    List<String> tickerNews = newsService.getRecentNewsHeadlines(share.getTicker());
                    instrumentData.put("ticker_news", tickerNews);
                }

                marketData.add(instrumentData);
            } catch (Exception e) {
                System.err.println("Ошибка при получении данных для FIGI " + share.getFigi() + ": " + e.getMessage());
            }
        }
        return ResponseEntity.ok(marketData);
    }

    @PostMapping("/execute-trade")
    public ResponseEntity<?> executeTrade(@RequestBody TradeRequest tradeRequest,
                                          @RequestParam(name = "simulate", defaultValue = "false") boolean simulate,
                                          @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to) {
        try {
            Instant effectiveTimeTo = to.orElse(Instant.now());
            List<HistoricCandle> candles = marketDataService.getHistoricCandles(
                    tradeRequest.getInstrumentFigi(),
                    7,
                    CandleInterval.CANDLE_INTERVAL_15_MIN,
                    effectiveTimeTo
            );
            if (candles.isEmpty()) {
                return ResponseEntity.badRequest().body("Не удалось получить данные для FIGI: " + tradeRequest.getInstrumentFigi());
            }
            Map<String, Double> indicators = indicatorService.calculateIndicators(candles);

            if (simulate) {
                if (openPositions.stream().anyMatch(p -> p.figi.equals(tradeRequest.getInstrumentFigi()))) {
                    return ResponseEntity.ok().body("Симуляция: Позиция по " + tradeRequest.getInstrumentFigi() + " уже открыта.");
                }

                if ("HOLD".equalsIgnoreCase(tradeRequest.getAction())) {
                    return ResponseEntity.ok().body("Симуляция: Действие 'HOLD' пропускается.");
                }

                HistoricCandle lastCandle = candles.get(candles.size() - 1);
                BigDecimal currentPrice = TinkoffApiUtils.quotationToBigDecimal(lastCandle.getClose());
                Instrument instrument = api.getInstrumentsService().getInstrumentByFigiSync(tradeRequest.getInstrumentFigi());
                int lotSize = instrument.getLot();
                long lotsToTrade = 1;

                BigDecimal tradeAmount = currentPrice.multiply(BigDecimal.valueOf(lotsToTrade * lotSize));
                BigDecimal commission = tradeAmount.multiply(commissionRate);
                BigDecimal totalCost = tradeAmount.add(commission);

                if (simulatedCash.compareTo(totalCost) < 0) {
                    return ResponseEntity.badRequest().body("Симуляция: Недостаточно средств для открытия позиции.");
                }

                double atrValue = indicators.getOrDefault("atr_14", 0.0);
                if (atrValue == 0.0) {
                    return ResponseEntity.badRequest().body("Симуляция: Не удалось рассчитать ATR.");
                }
                BigDecimal stopLossOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS);
                BigDecimal takeProfitOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_TAKE_PROFIT);

                if ("BUY".equalsIgnoreCase(tradeRequest.getAction())) {
                    BigDecimal stopLossPrice = currentPrice.subtract(stopLossOffset);
                    BigDecimal takeProfitPrice = currentPrice.add(takeProfitOffset);
                    simulatedCash = simulatedCash.subtract(totalCost);
                    openPositions.add(new SimulatedPosition(tradeRequest.getInstrumentFigi(), lotsToTrade, currentPrice, stopLossPrice, takeProfitPrice, lotSize, PositionType.LONG));
                    System.out.printf("СИМУЛЯЦИЯ: Покупка %d лотов %s по цене %s. SL: %s, TP: %s. Остаток: %s%n",
                            lotsToTrade, instrument.getTicker(), currentPrice.toPlainString(), stopLossPrice.setScale(4, RoundingMode.HALF_UP), takeProfitPrice.setScale(4, RoundingMode.HALF_UP), simulatedCash.setScale(2, RoundingMode.HALF_UP));
                } else if ("SELL".equalsIgnoreCase(tradeRequest.getAction())) {
                    BigDecimal stopLossPrice = currentPrice.add(stopLossOffset);
                    BigDecimal takeProfitPrice = currentPrice.subtract(takeProfitOffset);
                    openPositions.add(new SimulatedPosition(tradeRequest.getInstrumentFigi(), lotsToTrade, currentPrice, stopLossPrice, takeProfitPrice, lotSize, PositionType.SHORT));
                    System.out.printf("СИМУЛЯЦИЯ: Продажа (шорт) %d лотов %s по цене %s. SL: %s, TP: %s. Остаток: %s%n",
                            lotsToTrade, instrument.getTicker(), currentPrice.toPlainString(), stopLossPrice.setScale(4, RoundingMode.HALF_UP), takeProfitPrice.setScale(4, RoundingMode.HALF_UP), simulatedCash.setScale(2, RoundingMode.HALF_UP));
                }
                return ResponseEntity.ok().body("Симуляция: Сделка исполнена.");

            } else {
                orderService.executeTrade(tradeRequest, indicators);
                return ResponseEntity.ok().body("Торговый приказ получен и обработан.");
            }
        } catch (Exception e) {
            System.err.println("Критическая ошибка при исполнении сделки: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Ошибка на сервере: " + e.getMessage());
        }
    }

    private void checkAndCloseSimulatedPosition(SimulatedPosition position, HistoricCandle lastCandle) {
        BigDecimal currentLowPrice = TinkoffApiUtils.quotationToBigDecimal(lastCandle.getLow());
        BigDecimal currentHighPrice = TinkoffApiUtils.quotationToBigDecimal(lastCandle.getHigh());

        if (position.type == PositionType.LONG) {
            if (currentLowPrice.compareTo(position.stopLossPrice) <= 0) {
                closePosition(position, position.stopLossPrice, "СТОП-ЛОСС");
            } else if (currentHighPrice.compareTo(position.takeProfitPrice) >= 0) {
                closePosition(position, position.takeProfitPrice, "ТЕЙК-ПРОФИТ");
            }
        } else if (position.type == PositionType.SHORT) {
            if (currentHighPrice.compareTo(position.stopLossPrice) >= 0) {
                closePosition(position, position.stopLossPrice, "СТОП-ЛОСС");
            } else if (currentLowPrice.compareTo(position.takeProfitPrice) <= 0) {
                closePosition(position, position.takeProfitPrice, "ТЕЙК-ПРОФИТ");
            }
        }
    }

    private void closePosition(SimulatedPosition position, BigDecimal exitPrice, String reason) {
        if (position.type == PositionType.LONG) {
            BigDecimal saleAmount = exitPrice.multiply(BigDecimal.valueOf(position.lots * position.lotSize));
            BigDecimal commission = saleAmount.multiply(commissionRate);
            BigDecimal netProceeds = saleAmount.subtract(commission);
            simulatedCash = simulatedCash.add(netProceeds);
        } else if (position.type == PositionType.SHORT) {
            BigDecimal profitOrLoss = position.entryPrice.subtract(exitPrice).multiply(BigDecimal.valueOf(position.lots * position.lotSize));
            BigDecimal buybackAmount = exitPrice.multiply(BigDecimal.valueOf(position.lots * position.lotSize));
            BigDecimal commission = buybackAmount.multiply(commissionRate);
            simulatedCash = simulatedCash.add(profitOrLoss).subtract(commission);
        }

        System.out.printf("СИМУЛЯЦИЯ: Сработал %s по %s. Закрытие %d лотов по цене %s. Итоговый баланс: %s%n",
                reason, position.figi, position.lots, exitPrice.toPlainString(), simulatedCash.setScale(2, RoundingMode.HALF_UP));

        openPositions.remove(position);
    }
}
