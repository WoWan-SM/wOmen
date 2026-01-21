package com.example.tradingagent.controllers;

import com.example.tradingagent.services.BacktestEngine;
import com.example.tradingagent.services.TinkoffInstrumentsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.Share;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API для запуска бэктестов стратегии на исторических данных.
 */
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final BacktestEngine backtestEngine;
    private final TinkoffInstrumentsService instrumentsService;

    @Autowired
    public BacktestController(BacktestEngine backtestEngine, 
                             TinkoffInstrumentsService instrumentsService) {
        this.backtestEngine = backtestEngine;
        this.instrumentsService = instrumentsService;
    }

    /**
     * Запускает бэктест для указанного тикера
     * 
     * @param ticker Тикер инструмента (например, SBER)
     * @param days Количество дней для бэктеста (по умолчанию 30)
     * @param interval Интервал свечей: HOUR, DAY, 15_MIN (по умолчанию HOUR)
     * @return Результат бэктеста
     */
    @GetMapping("/run")
    public ResponseEntity<?> runBacktest(
            @RequestParam String ticker,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "HOUR") String interval) {
        try {
            // Получаем FIGI по тикеру
            List<Share> shares = instrumentsService.getBlueChips();
            Share share = shares.stream()
                    .filter(s -> s.getTicker().equalsIgnoreCase(ticker))
                    .findFirst()
                    .orElse(null);
            
            if (share == null) {
                return ResponseEntity.badRequest().body("Инструмент с тикером " + ticker + " не найден");
            }
            
            CandleInterval candleInterval;
            try {
                candleInterval = CandleInterval.valueOf("CANDLE_INTERVAL_" + interval);
            } catch (IllegalArgumentException e) {
                candleInterval = CandleInterval.CANDLE_INTERVAL_HOUR;
            }
            
            BacktestEngine.BacktestResult result = backtestEngine.runBacktest(
                    share.getFigi(), 
                    share.getTicker(), 
                    days, 
                    candleInterval);
            
            if (result == null) {
                return ResponseEntity.badRequest().body("Недостаточно данных для бэктеста");
            }
            
            // Формируем ответ
            Map<String, Object> response = new HashMap<>();
            response.put("ticker", result.getTicker());
            response.put("startDate", result.getStartDate());
            response.put("endDate", result.getEndDate());
            response.put("initialBalance", result.getInitialBalance());
            response.put("finalBalance", result.getFinalBalance());
            response.put("totalPnL", result.getTotalPnL());
            response.put("totalPnLPercent", result.getTotalPnLPercent());
            response.put("totalTrades", result.getTotalTrades());
            response.put("winningTrades", result.getWinningTrades());
            response.put("losingTrades", result.getLosingTrades());
            response.put("winRate", result.getTotalTrades() > 0 
                    ? (double) result.getWinningTrades() / result.getTotalTrades() * 100 
                    : 0.0);
            response.put("maxDrawdown", result.getMaxDrawdown());
            response.put("maxDrawdownPercent", result.getMaxDrawdownPercent());
            response.put("trades", result.getTrades().stream().map(trade -> {
                Map<String, Object> tradeMap = new HashMap<>();
                tradeMap.put("entryTime", trade.getEntryTime());
                tradeMap.put("exitTime", trade.getExitTime());
                tradeMap.put("entryPrice", trade.getEntryPrice());
                tradeMap.put("exitPrice", trade.getExitPrice());
                tradeMap.put("quantity", trade.getQuantity());
                tradeMap.put("pnl", trade.getPnL());
                tradeMap.put("pnlPercent", trade.getPnLPercent());
                tradeMap.put("reason", trade.getReason());
                return tradeMap;
            }).toList());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Ошибка при выполнении бэктеста: " + e.getMessage());
        }
    }
}
