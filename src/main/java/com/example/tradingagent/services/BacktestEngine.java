package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * Backtesting Engine для тестирования стратегии на исторических данных.
 * Прогоняет TechnicalIndicatorService по историческим данным и симулирует сделки без отправки в API.
 */
@Service
public class BacktestEngine {

    private static final Logger logger = LoggerFactory.getLogger(BacktestEngine.class);
    
    // Параметры стратегии (из roadmap)
    private static final double MIN_ADX_THRESHOLD = 25.0;
    private static final BigDecimal ATR_MULTIPLIER_STOP_LOSS = new BigDecimal("2.0");
    private static final BigDecimal ATR_MULTIPLIER_TAKE_PROFIT = new BigDecimal("3.0");
    private static final BigDecimal ESTIMATED_COMMISSION_RATE = new BigDecimal("0.0005"); // 0.05%
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000.00"); // Начальный баланс 100k руб

    private final TinkoffMarketDataService marketDataService;
    private final TechnicalIndicatorService indicatorService;

    public BacktestEngine(TinkoffMarketDataService marketDataService, TechnicalIndicatorService indicatorService) {
        this.marketDataService = marketDataService;
        this.indicatorService = indicatorService;
    }

    /**
     * Результат бэктеста
     */
    public static class BacktestResult {
        private final String ticker;
        private final Instant startDate;
        private final Instant endDate;
        private final BigDecimal initialBalance;
        private final BigDecimal finalBalance;
        private final BigDecimal totalPnL;
        private final BigDecimal totalPnLPercent;
        private final int totalTrades;
        private final int winningTrades;
        private final int losingTrades;
        private final BigDecimal maxDrawdown;
        private final BigDecimal maxDrawdownPercent;
        private final List<Trade> trades;

        public BacktestResult(String ticker, Instant startDate, Instant endDate, 
                             BigDecimal initialBalance, BigDecimal finalBalance,
                             int totalTrades, int winningTrades, int losingTrades,
                             BigDecimal maxDrawdown, BigDecimal maxDrawdownPercent,
                             List<Trade> trades) {
            this.ticker = ticker;
            this.startDate = startDate;
            this.endDate = endDate;
            this.initialBalance = initialBalance;
            this.finalBalance = finalBalance;
            this.totalPnL = finalBalance.subtract(initialBalance);
            this.totalPnLPercent = totalPnL.divide(initialBalance, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            this.totalTrades = totalTrades;
            this.winningTrades = winningTrades;
            this.losingTrades = losingTrades;
            this.maxDrawdown = maxDrawdown;
            this.maxDrawdownPercent = maxDrawdownPercent;
            this.trades = trades;
        }

        // Getters
        public String getTicker() { return ticker; }
        public Instant getStartDate() { return startDate; }
        public Instant getEndDate() { return endDate; }
        public BigDecimal getInitialBalance() { return initialBalance; }
        public BigDecimal getFinalBalance() { return finalBalance; }
        public BigDecimal getTotalPnL() { return totalPnL; }
        public BigDecimal getTotalPnLPercent() { return totalPnLPercent; }
        public int getTotalTrades() { return totalTrades; }
        public int getWinningTrades() { return winningTrades; }
        public int getLosingTrades() { return losingTrades; }
        public BigDecimal getMaxDrawdown() { return maxDrawdown; }
        public BigDecimal getMaxDrawdownPercent() { return maxDrawdownPercent; }
        public List<Trade> getTrades() { return trades; }
    }

    /**
     * Симулированная сделка
     */
    public static class Trade {
        private final Instant entryTime;
        private final Instant exitTime;
        private final BigDecimal entryPrice;
        private final BigDecimal exitPrice;
        private final BigDecimal quantity;
        private final BigDecimal pnl;
        private final BigDecimal pnlPercent;
        private final String reason;

        public Trade(Instant entryTime, Instant exitTime, BigDecimal entryPrice, 
                    BigDecimal exitPrice, BigDecimal quantity, BigDecimal pnl, 
                    BigDecimal pnlPercent, String reason) {
            this.entryTime = entryTime;
            this.exitTime = exitTime;
            this.entryPrice = entryPrice;
            this.exitPrice = exitPrice;
            this.quantity = quantity;
            this.pnl = pnl;
            this.pnlPercent = pnlPercent;
            this.reason = reason;
        }

        // Getters
        public Instant getEntryTime() { return entryTime; }
        public Instant getExitTime() { return exitTime; }
        public BigDecimal getEntryPrice() { return entryPrice; }
        public BigDecimal getExitPrice() { return exitPrice; }
        public BigDecimal getQuantity() { return quantity; }
        public BigDecimal getPnL() { return pnl; }
        public BigDecimal getPnLPercent() { return pnlPercent; }
        public String getReason() { return reason; }
    }

    /**
     * Запускает бэктест для указанного инструмента за период
     *
     * @param figi FIGI инструмента
     * @param ticker Тикер инструмента
     * @param days Количество дней для бэктеста
     * @param interval Интервал свечей
     * @return Результат бэктеста
     */
    public BacktestResult runBacktest(String figi, String ticker, int days, CandleInterval interval) {
        logger.info("Запуск бэктеста для {} за {} дней", ticker, days);
        
        Instant endDate = Instant.now();
        List<HistoricCandle> candles = marketDataService.getHistoricCandles(figi, days, interval, endDate);
        
        if (candles.size() < 120) {
            logger.warn("Недостаточно свечей для бэктеста: {}", candles.size());
            return null;
        }

        Instant startDate = Instant.ofEpochSecond(candles.get(0).getTime().getSeconds());
        
        BigDecimal balance = INITIAL_BALANCE;
        BigDecimal peakBalance = INITIAL_BALANCE;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal maxDrawdownPercent = BigDecimal.ZERO;
        
        List<Trade> trades = new ArrayList<>();
        Position currentPosition = null;
        
        // Проходим по свечам, начиная с индекса 120 (чтобы были индикаторы)
        for (int i = 120; i < candles.size(); i++) {
            List<HistoricCandle> historyCandles = candles.subList(0, i + 1);
            Map<String, Double> indicators = indicatorService.calculateIndicators(historyCandles, ticker, figi);
            
            if (indicators.isEmpty()) {
                continue;
            }
            
            HistoricCandle currentCandle = candles.get(i);
            BigDecimal currentPrice = TinkoffApiUtils.quotationToBigDecimal(currentCandle.getClose());
            Instant currentTime = Instant.ofEpochSecond(currentCandle.getTime().getSeconds());
            
            // Если есть открытая позиция - проверяем SL/TP
            if (currentPosition != null) {
                // Проверка стоп-лосса и тейк-профита
                boolean shouldExit = false;
                String exitReason = "";
                
                if (currentPosition.isLong) {
                    // LONG позиция
                    if (currentPrice.compareTo(currentPosition.stopLoss) <= 0) {
                        shouldExit = true;
                        exitReason = "STOP_LOSS";
                    } else if (currentPrice.compareTo(currentPosition.takeProfit) >= 0) {
                        shouldExit = true;
                        exitReason = "TAKE_PROFIT";
                    }
                } else {
                    // SHORT позиция
                    if (currentPrice.compareTo(currentPosition.stopLoss) >= 0) {
                        shouldExit = true;
                        exitReason = "STOP_LOSS";
                    } else if (currentPrice.compareTo(currentPosition.takeProfit) <= 0) {
                        shouldExit = true;
                        exitReason = "TAKE_PROFIT";
                    }
                }
                
                if (shouldExit) {
                    // Закрываем позицию
                    BigDecimal exitPnL = calculateFinalPnL(currentPosition, currentPrice);
                    balance = balance.add(exitPnL);
                    
                    BigDecimal pnlPercent = exitPnL.divide(
                        currentPosition.entryPrice.multiply(currentPosition.quantity), 
                        4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    
                    trades.add(new Trade(
                        currentPosition.entryTime,
                        currentTime,
                        currentPosition.entryPrice,
                        currentPrice,
                        currentPosition.quantity,
                        exitPnL,
                        pnlPercent,
                        exitReason
                    ));
                    
                    currentPosition = null;
                }
            } else {
                // Нет позиции - ищем точку входа
                double adx = indicators.getOrDefault("adx_14", 0.0);
                double rsi = indicators.getOrDefault("rsi_14", 50.0);
                double macdHist = indicators.getOrDefault("macd_hist", 0.0);
                double macdHistPrev = indicators.getOrDefault("macd_hist_previous", 0.0);
                double ema100 = indicators.getOrDefault("ema_100", 0.0);
                double atr = indicators.getOrDefault("atr_14", 0.0);
                
                // Фильтр ADX
                if (adx < MIN_ADX_THRESHOLD) {
                    continue;
                }
                
                // Простая стратегия: BUY если цена выше EMA100, MACD положительный и растет
                boolean buySignal = currentPrice.compareTo(BigDecimal.valueOf(ema100)) > 0 
                        && macdHist > 0 
                        && macdHist > macdHistPrev
                        && rsi < 70; // Не перекуплен
                
                if (buySignal && balance.compareTo(BigDecimal.valueOf(10000)) > 0) {
                    // Открываем LONG позицию
                    BigDecimal tradeAmount = balance.multiply(BigDecimal.valueOf(0.1)); // 10% баланса
                    BigDecimal quantity = tradeAmount.divide(currentPrice, 2, RoundingMode.FLOOR);
                    
                    if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal stopLoss = currentPrice.subtract(
                            BigDecimal.valueOf(atr).multiply(ATR_MULTIPLIER_STOP_LOSS));
                        BigDecimal takeProfit = currentPrice.add(
                            BigDecimal.valueOf(atr).multiply(ATR_MULTIPLIER_TAKE_PROFIT));
                        
                        currentPosition = new Position(
                            currentTime,
                            currentPrice,
                            quantity,
                            stopLoss,
                            takeProfit,
                            true
                        );
                        
                        // Вычитаем комиссию
                        BigDecimal commission = tradeAmount.multiply(ESTIMATED_COMMISSION_RATE);
                        balance = balance.subtract(commission);
                    }
                }
            }
            
            // Обновляем пик и просадку
            if (balance.compareTo(peakBalance) > 0) {
                peakBalance = balance;
            }
            BigDecimal drawdown = peakBalance.subtract(balance);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
                maxDrawdownPercent = drawdown.divide(peakBalance, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }
        
        // Закрываем последнюю позицию, если есть
        if (currentPosition != null) {
            HistoricCandle lastCandle = candles.get(candles.size() - 1);
            BigDecimal lastPrice = TinkoffApiUtils.quotationToBigDecimal(lastCandle.getClose());
            Instant lastTime = Instant.ofEpochSecond(lastCandle.getTime().getSeconds());
            
            BigDecimal exitPnL = calculateFinalPnL(currentPosition, lastPrice);
            balance = balance.add(exitPnL);
            
            BigDecimal pnlPercent = exitPnL.divide(
                currentPosition.entryPrice.multiply(currentPosition.quantity), 
                4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            
            trades.add(new Trade(
                currentPosition.entryTime,
                lastTime,
                currentPosition.entryPrice,
                lastPrice,
                currentPosition.quantity,
                exitPnL,
                pnlPercent,
                "END_OF_PERIOD"
            ));
        }
        
        // Подсчитываем статистику
        int winningTrades = 0;
        int losingTrades = 0;
        for (Trade trade : trades) {
            if (trade.getPnL().compareTo(BigDecimal.ZERO) > 0) {
                winningTrades++;
            } else {
                losingTrades++;
            }
        }
        
        return new BacktestResult(
            ticker,
            startDate,
            endDate,
            INITIAL_BALANCE,
            balance,
            trades.size(),
            winningTrades,
            losingTrades,
            maxDrawdown,
            maxDrawdownPercent,
            trades
        );
    }
    
    private BigDecimal calculatePnL(Position position, BigDecimal currentPrice) {
        if (position.isLong) {
            return currentPrice.subtract(position.entryPrice).multiply(position.quantity);
        } else {
            return position.entryPrice.subtract(currentPrice).multiply(position.quantity);
        }
    }
    
    private BigDecimal calculateFinalPnL(Position position, BigDecimal exitPrice) {
        BigDecimal grossPnL = calculatePnL(position, exitPrice);
        BigDecimal tradeValue = position.entryPrice.multiply(position.quantity);
        BigDecimal commission = tradeValue.multiply(ESTIMATED_COMMISSION_RATE).multiply(BigDecimal.valueOf(2)); // Вход + выход
        return grossPnL.subtract(commission);
    }
    
    private static class Position {
        final Instant entryTime;
        final BigDecimal entryPrice;
        final BigDecimal quantity;
        final BigDecimal stopLoss;
        final BigDecimal takeProfit;
        final boolean isLong;
        
        Position(Instant entryTime, BigDecimal entryPrice, BigDecimal quantity,
                BigDecimal stopLoss, BigDecimal takeProfit, boolean isLong) {
            this.entryTime = entryTime;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.isLong = isLong;
        }
    }
}
