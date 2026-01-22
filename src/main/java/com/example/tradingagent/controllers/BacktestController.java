package com.example.tradingagent.controllers;

import com.example.tradingagent.services.BacktestEngine;
import com.example.tradingagent.services.SharedBacktestBalance;
import com.example.tradingagent.services.TinkoffInstrumentsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.Share;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * REST API для запуска бэктестов стратегии на исторических данных.
 */
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private static final Logger logger = LoggerFactory.getLogger(BacktestController.class);
    
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
     * @param days Количество дней для бэктеста (по умолчанию 10)
     * @param interval Интервал свечей: HOUR, DAY, 15_MIN (по умолчанию 5_MIN)
     * @return Результат бэктеста
     */
    @GetMapping("/run")
    public ResponseEntity<?> runBacktest(
            @RequestParam String ticker,
            @RequestParam(defaultValue = "20") int days,
            @RequestParam(defaultValue = "15_MIN") String interval) {
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
                    candleInterval,
                    null, // null = отдельный баланс для одного тикера
                    null, // atrMultiplierStopLoss - используем значение по умолчанию
                    null, // atrMultiplierTakeProfit - используем значение по умолчанию
                    null); // atrMultiplierBreakeven - используем значение по умолчанию
            
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

    /**
     * Запускает бэктест для всех blue chips тикеров и возвращает агрегированные результаты
     * 
     * @param days Количество дней для бэктеста (по умолчанию 20)
     * @param interval Интервал свечей: HOUR, DAY, 15_MIN (по умолчанию 15_MIN)
     * @return Агрегированные результаты бэктеста по всем тикерам
     */
    @GetMapping("/run-all")
    public ResponseEntity<?> runBacktestAll(
            @RequestParam(defaultValue = "20") int days,
            @RequestParam(defaultValue = "15_MIN") String interval) {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try {
            List<Share> shares = instrumentsService.getBlueChips();
            
            CandleInterval tempCandleInterval;
            try {
                tempCandleInterval = CandleInterval.valueOf("CANDLE_INTERVAL_" + interval);
            } catch (IllegalArgumentException e) {
                tempCandleInterval = CandleInterval.CANDLE_INTERVAL_HOUR;
            }
            final CandleInterval candleInterval = tempCandleInterval;
            
            // Общий начальный баланс для всех тикеров
            final BigDecimal commonInitialBalance = new BigDecimal("10000.00");
            final SharedBacktestBalance sharedBalance = new SharedBacktestBalance(commonInitialBalance);
            final int finalDays = days;
            
            // Распараллеливаем выполнение бэктестов
            List<CompletableFuture<BacktestEngine.BacktestResult>> futures = shares.stream()
                    .map(share -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return backtestEngine.runBacktest(
                                    share.getFigi(), 
                                    share.getTicker(), 
                                    finalDays, 
                                    candleInterval,
                                    sharedBalance, // Передаем общий баланс
                                    null, // atrMultiplierStopLoss - используем значение по умолчанию
                                    null, // atrMultiplierTakeProfit - используем значение по умолчанию
                                    null); // atrMultiplierBreakeven - используем значение по умолчанию
                        } catch (Exception e) {
                            System.err.println("Ошибка бэктеста для " + share.getTicker() + ": " + e.getMessage());
                            return null;
                        }
                    }, executor))
                    .collect(Collectors.toList());
            
            // Ждем завершения всех бэктестов
            List<BacktestEngine.BacktestResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            if (results.isEmpty()) {
                return ResponseEntity.badRequest().body("Не удалось выполнить бэктест ни для одного тикера");
            }
            
            // Агрегируем результаты - используем общий счет для всех тикеров
            BigDecimal totalFinalBalance = sharedBalance.getTotalBalance();
            BigDecimal totalPnL = totalFinalBalance.subtract(commonInitialBalance);
            BigDecimal totalPnLPercent = commonInitialBalance.compareTo(BigDecimal.ZERO) > 0
                    ? totalPnL.divide(commonInitialBalance, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;
            
            // Сохраняем результаты по тикерам
            List<Map<String, Object>> tickerResults = results.stream()
                    .map(result -> {
                        Map<String, Object> tickerResult = new HashMap<>();
                        tickerResult.put("ticker", result.getTicker());
                        tickerResult.put("finalBalance", result.getFinalBalance());
                        tickerResult.put("totalPnL", result.getTotalPnL());
                        tickerResult.put("totalPnLPercent", result.getTotalPnLPercent());
                        tickerResult.put("totalTrades", result.getTotalTrades());
                        tickerResult.put("winningTrades", result.getWinningTrades());
                        tickerResult.put("losingTrades", result.getLosingTrades());
                        tickerResult.put("winRate", result.getTotalTrades() > 0 
                                ? (double) result.getWinningTrades() / result.getTotalTrades() * 100 
                                : 0.0);
                        tickerResult.put("maxDrawdown", result.getMaxDrawdown());
                        tickerResult.put("maxDrawdownPercent", result.getMaxDrawdownPercent());
                        return tickerResult;
                    })
                    .collect(Collectors.toList());
            
            int totalTrades = results.stream()
                    .mapToInt(BacktestEngine.BacktestResult::getTotalTrades)
                    .sum();
            int totalWinningTrades = results.stream()
                    .mapToInt(BacktestEngine.BacktestResult::getWinningTrades)
                    .sum();
            int totalLosingTrades = results.stream()
                    .mapToInt(BacktestEngine.BacktestResult::getLosingTrades)
                    .sum();
            
            BigDecimal maxDrawdown = sharedBalance.getMaxDrawdown();
            BigDecimal maxDrawdownPercent = sharedBalance.getMaxDrawdownPercent();
            
            // Общее количество сделок из всех тикеров
            int totalAllTrades = results.stream()
                    .mapToInt(r -> r.getTrades().size())
                    .sum();
            
            // Формируем ответ
            Map<String, Object> summary = new HashMap<>();
            summary.put("totalTickers", results.size());
            summary.put("initialBalance", commonInitialBalance);
            summary.put("totalFinalBalance", totalFinalBalance);
            summary.put("totalPnL", totalPnL);
            summary.put("totalPnLPercent", totalPnLPercent);
            summary.put("totalTrades", totalTrades);
            summary.put("totalWinningTrades", totalWinningTrades);
            summary.put("totalLosingTrades", totalLosingTrades);
            summary.put("overallWinRate", totalTrades > 0 
                    ? (double) totalWinningTrades / totalTrades * 100 
                    : 0.0);
            summary.put("maxDrawdown", maxDrawdown);
            summary.put("maxDrawdownPercent", maxDrawdownPercent);
            summary.put("totalAllTrades", totalAllTrades);
            
            Map<String, Object> response = new HashMap<>();
            response.put("summary", summary);
            response.put("tickerResults", tickerResults);
            response.put("days", days);
            response.put("interval", interval);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Ошибка при выполнении бэктеста: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Оптимизирует параметры ATR multipliers для максимального финального баланса
     * 
     * @param days Количество дней для бэктеста (по умолчанию 20)
     * @param interval Интервал свечей: HOUR, DAY, 15_MIN (по умолчанию 15_MIN)
     * @param stopLossMin Минимальное значение для ATR_MULTIPLIER_STOP_LOSS (по умолчанию 1.0)
     * @param stopLossMax Максимальное значение для ATR_MULTIPLIER_STOP_LOSS (по умолчанию 4.0)
     * @param stopLossStep Шаг для ATR_MULTIPLIER_STOP_LOSS (по умолчанию 0.5)
     * @param takeProfitMin Минимальное значение для ATR_MULTIPLIER_TAKE_PROFIT (по умолчанию 2.0)
     * @param takeProfitMax Максимальное значение для ATR_MULTIPLIER_TAKE_PROFIT (по умолчанию 6.0)
     * @param takeProfitStep Шаг для ATR_MULTIPLIER_TAKE_PROFIT (по умолчанию 0.5)
     * @param breakevenMin Минимальное значение для ATR_MULTIPLIER_BREAKEVEN (по умолчанию 0.5)
     * @param breakevenMax Максимальное значение для ATR_MULTIPLIER_BREAKEVEN (по умолчанию 2.0)
     * @param breakevenStep Шаг для ATR_MULTIPLIER_BREAKEVEN (по умолчанию 0.5)
     * @return Результат оптимизации с оптимальными параметрами
     */
    @GetMapping("/optimize")
    public ResponseEntity<?> optimizeParameters(
            @RequestParam(defaultValue = "20") int days,
            @RequestParam(defaultValue = "15_MIN") String interval,
            @RequestParam(defaultValue = "1.0") double stopLossMin,
            @RequestParam(defaultValue = "4.0") double stopLossMax,
            @RequestParam(defaultValue = "0.5") double stopLossStep,
            @RequestParam(defaultValue = "2.0") double takeProfitMin,
            @RequestParam(defaultValue = "6.0") double takeProfitMax,
            @RequestParam(defaultValue = "0.5") double takeProfitStep,
            @RequestParam(defaultValue = "0.5") double breakevenMin,
            @RequestParam(defaultValue = "2.0") double breakevenMax,
            @RequestParam(defaultValue = "0.5") double breakevenStep) {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try {
            List<Share> shares = instrumentsService.getBlueChips();
            
            CandleInterval tempCandleInterval;
            try {
                tempCandleInterval = CandleInterval.valueOf("CANDLE_INTERVAL_" + interval);
            } catch (IllegalArgumentException e) {
                tempCandleInterval = CandleInterval.CANDLE_INTERVAL_HOUR;
            }
            final CandleInterval candleInterval = tempCandleInterval;
            
            final int finalDays = days;
            
            // Генерируем все комбинации параметров
            List<Map<String, BigDecimal>> parameterCombinations = new ArrayList<>();
            for (double sl = stopLossMin; sl <= stopLossMax; sl += stopLossStep) {
                for (double tp = takeProfitMin; tp <= takeProfitMax; tp += takeProfitStep) {
                    for (double be = breakevenMin; be <= breakevenMax; be += breakevenStep) {
                        Map<String, BigDecimal> params = new HashMap<>();
                        params.put("stopLoss", BigDecimal.valueOf(sl));
                        params.put("takeProfit", BigDecimal.valueOf(tp));
                        params.put("breakeven", BigDecimal.valueOf(be));
                        parameterCombinations.add(params);
                    }
                }
            }
            
            logger.info("Начинаем оптимизацию: {} комбинаций параметров для {} тикеров", 
                    parameterCombinations.size(), shares.size());
            
            // Запускаем оптимизацию для каждой комбинации параметров
            List<CompletableFuture<Map<String, Object>>> optimizationFutures = parameterCombinations.stream()
                    .map(params -> CompletableFuture.supplyAsync(() -> {
                        try {
                            // Создаем общий баланс для этой комбинации параметров
                            BigDecimal initialBalance = new BigDecimal("10000.00");
                            SharedBacktestBalance sharedBalance = new SharedBacktestBalance(initialBalance);
                            
                            // Запускаем бэктест для всех тикеров с этими параметрами
                            List<BacktestEngine.BacktestResult> results = new ArrayList<>();
                            for (Share share : shares) {
                                try {
                                    BacktestEngine.BacktestResult result = backtestEngine.runBacktest(
                                            share.getFigi(),
                                            share.getTicker(),
                                            finalDays,
                                            candleInterval,
                                            sharedBalance,
                                            params.get("stopLoss"),
                                            params.get("takeProfit"),
                                            params.get("breakeven"));
                                    if (result != null) {
                                        results.add(result);
                                    }
                                } catch (Exception e) {
                                    // Пропускаем ошибки для отдельных тикеров
                                }
                            }
                            
                            if (results.isEmpty()) {
                                return null;
                            }
                            
                            BigDecimal finalBalance = sharedBalance.getTotalBalance();
                            BigDecimal totalPnL = finalBalance.subtract(initialBalance);
                            int totalTrades = results.stream()
                                    .mapToInt(BacktestEngine.BacktestResult::getTotalTrades)
                                    .sum();
                            
                            Map<String, Object> result = new HashMap<>();
                            result.put("stopLoss", params.get("stopLoss"));
                            result.put("takeProfit", params.get("takeProfit"));
                            result.put("breakeven", params.get("breakeven"));
                            result.put("finalBalance", finalBalance);
                            result.put("totalPnL", totalPnL);
                            result.put("totalPnLPercent", totalPnL.divide(initialBalance, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100)));
                            result.put("totalTrades", totalTrades);
                            result.put("maxDrawdown", sharedBalance.getMaxDrawdown());
                            result.put("maxDrawdownPercent", sharedBalance.getMaxDrawdownPercent());
                            
                            return result;
                        } catch (Exception e) {
                            logger.error("Ошибка оптимизации для параметров {}: {}", params, e.getMessage());
                            return null;
                        }
                    }, executor))
                    .collect(Collectors.toList());
            
            // Ждем завершения всех оптимизаций
            List<Map<String, Object>> optimizationResults = optimizationFutures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            if (optimizationResults.isEmpty()) {
                return ResponseEntity.badRequest().body("Не удалось выполнить оптимизацию ни для одной комбинации параметров");
            }
            
            // Находим оптимальную комбинацию (максимальный финальный баланс)
            Map<String, Object> bestResult = optimizationResults.stream()
                    .max(Comparator.comparing(r -> (BigDecimal) r.get("finalBalance")))
                    .orElse(null);
            
            if (bestResult == null) {
                return ResponseEntity.badRequest().body("Не удалось найти оптимальную комбинацию");
            }
            
            // Формируем ответ
            Map<String, Object> bestParameters = new HashMap<>();
            bestParameters.put("atrMultiplierStopLoss", bestResult.get("stopLoss"));
            bestParameters.put("atrMultiplierTakeProfit", bestResult.get("takeProfit"));
            bestParameters.put("atrMultiplierBreakeven", bestResult.get("breakeven"));
            
            Map<String, Object> response = new HashMap<>();
            response.put("bestParameters", bestParameters);
            response.put("bestResult", bestResult);
            response.put("totalCombinations", parameterCombinations.size());
            response.put("successfulCombinations", optimizationResults.size());
            
            // Сортируем результаты по финальному балансу (топ-10)
            List<Map<String, Object>> topResults = optimizationResults.stream()
                    .sorted((a, b) -> ((BigDecimal) b.get("finalBalance")).compareTo((BigDecimal) a.get("finalBalance")))
                    .limit(10)
                    .collect(Collectors.toList());
            response.put("top10Results", topResults);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Ошибка при оптимизации параметров: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }
}
