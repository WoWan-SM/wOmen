package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApi;
import com.example.tradingagent.TinkoffApiUtils;
import com.example.tradingagent.dto.TradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;
import ru.tinkoff.piapi.contract.v1.Instrument;
import ru.tinkoff.piapi.core.InvestApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Backtesting Engine для тестирования стратегии на исторических данных.
 * Прогоняет TechnicalIndicatorService по историческим данным и симулирует сделки без отправки в API.
 * Соответствует логике реальных торгов из TinkoffOrderService.
 */
@Service
public class BacktestEngine {

    private static final Logger logger = LoggerFactory.getLogger(BacktestEngine.class);
    
    // Параметры стратегии (из TinkoffOrderService)
    private static final BigDecimal ATR_MULTIPLIER_STOP_LOSS = new BigDecimal("2.0");
    private static final BigDecimal ATR_MULTIPLIER_TAKE_PROFIT = new BigDecimal("3.0");
    private static final BigDecimal ATR_MULTIPLIER_BREAKEVEN = new BigDecimal("1.0");
    private static final BigDecimal MIN_PROFIT_TO_COMMISSION_RATIO = new BigDecimal("3.0");
    private static final BigDecimal ESTIMATED_COMMISSION_RATE = new BigDecimal("0.0005"); // 0.05%
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000.00"); // Начальный баланс 100k руб
    private static final long COOLDOWN_MINUTES = 0; // Коулдаун после сделки

    private final TinkoffMarketDataService marketDataService;
    private final TechnicalIndicatorService indicatorService;
    private final RiskManagementService riskManagementService;
    private final TradingStateMachine stateMachine;
    private final N8nSignalService n8nSignalService;
    private final InvestApi api;

    public BacktestEngine(TinkoffMarketDataService marketDataService, 
                         TechnicalIndicatorService indicatorService,
                         RiskManagementService riskManagementService,
                         TradingStateMachine stateMachine,
                         N8nSignalService n8nSignalService) {
        this.marketDataService = marketDataService;
        this.indicatorService = indicatorService;
        this.riskManagementService = riskManagementService;
        this.stateMachine = stateMachine;
        this.n8nSignalService = n8nSignalService;
        this.api = TinkoffApi.getApi();
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
     * Торговля идет на свечах M15, индикаторы рассчитываются из H1 и M15
     *
     * @param figi FIGI инструмента
     * @param ticker Тикер инструмента
     * @param days Количество дней для бэктеста (максимум 10 для M15)
     * @param interval Интервал свечей (должен быть M15 для торговли)
     * @param sharedBalance Общий баланс для всех тикеров (null для отдельного баланса на тикер)
     * @param atrMultiplierStopLoss Множитель ATR для стоп-лосса (null для значения по умолчанию)
     * @param atrMultiplierTakeProfit Множитель ATR для тейк-профита (null для значения по умолчанию)
     * @param atrMultiplierBreakeven Множитель ATR для безубытка (null для значения по умолчанию)
     * @return Результат бэктеста
     */
    public BacktestResult runBacktest(String figi, String ticker, int days, CandleInterval interval, 
                                     SharedBacktestBalance sharedBalance,
                                     BigDecimal atrMultiplierStopLoss, 
                                     BigDecimal atrMultiplierTakeProfit, 
                                     BigDecimal atrMultiplierBreakeven) {
        logger.info("Запуск бэктеста для {} за {} дней", ticker, days);
        
        // Получаем информацию об инструменте
        Instrument instrument;
        try {
            instrument = api.getInstrumentsService().getInstrumentByFigiSync(figi);
        } catch (Exception e) {
            logger.error("Ошибка получения инструмента: {}", e.getMessage());
            return null;
        }
        
        // Ограничение: для M15 можно запросить максимум 10 дней
        int daysM15 = Math.max(days, 10);
        int daysH1 = Math.max(days, 30); // Для H1 нужно больше дней для расчета индикаторов
        
        Instant endDate = Instant.now();
        
        // Получаем свечи M15 (основной таймфрейм для торговли)
        List<HistoricCandle> candlesM15 = marketDataService.getHistoricCandles(
            figi, daysM15, CandleInterval.CANDLE_INTERVAL_15_MIN, endDate);
        
        if (candlesM15.size() < 120) {
            logger.warn("Недостаточно M15 свечей для бэктеста: {} (требуется минимум 120)", candlesM15.size());
            return null;
        }

        Instant startDate = Instant.ofEpochSecond(candlesM15.get(0).getTime().getSeconds());
        
        // Используем переданные параметры или значения по умолчанию
        BigDecimal stopLossMultiplier = atrMultiplierStopLoss != null ? atrMultiplierStopLoss : ATR_MULTIPLIER_STOP_LOSS;
        BigDecimal takeProfitMultiplier = atrMultiplierTakeProfit != null ? atrMultiplierTakeProfit : ATR_MULTIPLIER_TAKE_PROFIT;
        BigDecimal breakevenMultiplier = atrMultiplierBreakeven != null ? atrMultiplierBreakeven : ATR_MULTIPLIER_BREAKEVEN;
        
        // Используем общий баланс или создаем локальный для этого тикера
        boolean useSharedBalance = sharedBalance != null;
        
        // Локальные переменные для отслеживания баланса этого тикера (для статистики)
        BigDecimal tickerInitialBalance = useSharedBalance && sharedBalance != null 
            ? sharedBalance.getAvailableBalance() 
            : INITIAL_BALANCE;
        BigDecimal tickerFinalBalance = tickerInitialBalance;
        
        // Локальные переменные для работы с балансом (если не используется общий)
        BigDecimal availableBalance = useSharedBalance ? null : INITIAL_BALANCE;
        BigDecimal lockedBalance = useSharedBalance ? null : BigDecimal.ZERO;
        BigDecimal totalBalance = useSharedBalance ? null : INITIAL_BALANCE;
        BigDecimal peakBalance = useSharedBalance ? null : INITIAL_BALANCE;
        BigDecimal maxDrawdown = useSharedBalance ? null : BigDecimal.ZERO;
        BigDecimal maxDrawdownPercent = useSharedBalance ? null : BigDecimal.ZERO;
        
        List<Trade> trades = new ArrayList<>();
        Position currentPosition = null;
        Instant lastTradeTime = null; // Время последней сделки для коулдауна
        
        // Сбрасываем состояние для этого тикера
        stateMachine.resetToScanning(figi);
        
        // Получаем H1 свечи один раз за весь период (оптимизация)
        List<HistoricCandle> allCandlesH1 = marketDataService.getHistoricCandles(
            figi, daysH1, CandleInterval.CANDLE_INTERVAL_HOUR, endDate);
        
        if (allCandlesH1.size() < 120) {
            logger.warn("Недостаточно H1 свечей для расчета индикаторов: {} (требуется минимум 120)", allCandlesH1.size());
            return null;
        }
        
        // Проходим по M15 свечам, начиная с индекса 120 (чтобы были индикаторы)
        // Для каждой M15 свечи получаем актуальные H1 и M15 индикаторы
        for (int i = 120; i < candlesM15.size(); i++) {
            HistoricCandle currentCandle = candlesM15.get(i);
            BigDecimal currentPrice = TinkoffApiUtils.quotationToBigDecimal(currentCandle.getClose());
            Instant currentTime = Instant.ofEpochSecond(currentCandle.getTime().getSeconds());
            
            // Фильтруем H1 свечи до текущего момента (для расчета H1 индикаторов)
            List<HistoricCandle> candlesH1 = allCandlesH1.stream()
                .filter(c -> {
                    Instant candleTime = Instant.ofEpochSecond(c.getTime().getSeconds());
                    return !candleTime.isAfter(currentTime);
                })
                .toList();
            
            if (candlesH1.size() < 120) {
                continue;
            }
            
            // Рассчитываем H1 индикаторы
            Map<String, Double> indicatorsH1 = indicatorService.calculateIndicators(candlesH1, ticker, figi);
            
            if (indicatorsH1.isEmpty()) {
                continue;
            }
            
            // Получаем M15 свечи до текущего момента (для расчета M15 индикаторов)
            List<HistoricCandle> candlesM15ForIndicators = candlesM15.subList(0, i + 1);
            
            if (candlesM15ForIndicators.size() < 120) {
                continue;
            }
            
            // Рассчитываем M15 индикаторы
            Map<String, Double> indicatorsM15 = indicatorService.calculateIndicators(candlesM15ForIndicators, ticker, figi);
            
            if (indicatorsM15.isEmpty()) {
                continue;
            }
            
            // Если есть открытая позиция - проверяем SL/TP и трейлинг стоп
            // Пропускаем проверку canTrade() для инструментов с открытыми позициями
            if (currentPosition != null) {
                // Проверяем, что состояние ACTIVE (позиция открыта)
                TradingStateMachine.TradingStateType state = stateMachine.getState(figi);
                if (state != TradingStateMachine.TradingStateType.ACTIVE) {
                    // Состояние не соответствует - сбрасываем позицию
                    logger.warn("Несоответствие состояния для {}: позиция открыта, но состояние {}", figi, state);
                    currentPosition = null;
                    stateMachine.resetToScanning(figi);
                    continue;
                }
                // Обновляем трейлинг стоп с безубытком (используем H1 индикаторы для ATR)
                updateTrailingStop(currentPosition, currentPrice, indicatorsH1, instrument, 
                                  stopLossMultiplier, breakevenMultiplier);
                
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
                    // В реальной торговле: возвращаем заблокированные средства + PnL - комиссия за выход
                    BigDecimal quantity = BigDecimal.valueOf(currentPosition.lots * instrument.getLot());
                    BigDecimal entryValue = currentPosition.entryPrice.multiply(quantity); // БЕЗ комиссии за вход
                    BigDecimal exitValue = currentPrice.multiply(quantity);
                    
                    // Gross PnL (без учета комиссий)
                    BigDecimal grossPnL = currentPosition.isLong 
                        ? exitValue.subtract(entryValue)
                        : entryValue.subtract(exitValue);
                    
                    // Комиссия за выход
                    BigDecimal exitCommission = exitValue.multiply(ESTIMATED_COMMISSION_RATE);
                    
                    // Net PnL (с учетом комиссии за выход)
                    // Комиссия за вход уже была вычтена при открытии из availableBalance
                    BigDecimal netPnL = grossPnL.subtract(exitCommission);
                    
                    // Возвращаем заблокированные средства + Net PnL
                    // lockedBalance = entryValue (без комиссии)
                    // netPnL уже учитывает комиссию за выход
                    if (useSharedBalance && sharedBalance != null) {
                        sharedBalance.closePosition(entryValue, netPnL);
                    } else if (availableBalance != null && lockedBalance != null) {
                        availableBalance = availableBalance.add(lockedBalance).add(netPnL);
                        lockedBalance = BigDecimal.ZERO;
                        totalBalance = availableBalance;
                    }
                    
                    // Для расчета процента используем стоимость входа + комиссия за вход
                    BigDecimal entryValueWithCommission = entryValue.multiply(
                        BigDecimal.ONE.add(ESTIMATED_COMMISSION_RATE));
                    BigDecimal pnlPercent = netPnL.divide(entryValueWithCommission, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                    
                    boolean wasLoss = netPnL.compareTo(BigDecimal.ZERO) < 0;
                    
                    trades.add(new Trade(
                        currentPosition.entryTime,
                        currentTime,
                        currentPosition.entryPrice,
                        currentPrice,
                        quantity,
                        netPnL,
                        pnlPercent,
                        exitReason
                    ));
                    
                    // STATE MACHINE: Переход в COOLDOWN
                    stateMachine.setCooldown(figi, wasLoss);
                    lastTradeTime = currentTime;
                    currentPosition = null;
                } else {
                    // Обновляем общий баланс с учетом текущего unrealized PnL
                    BigDecimal quantity = BigDecimal.valueOf(currentPosition.lots * instrument.getLot());
                    BigDecimal entryValue = currentPosition.entryPrice.multiply(quantity);
                    BigDecimal currentValue = currentPrice.multiply(quantity);
                    
                    BigDecimal unrealizedPnL = currentPosition.isLong
                        ? currentValue.subtract(entryValue)
                        : entryValue.subtract(currentValue);
                    
                    // Общий баланс = свободные + заблокированные + unrealized PnL
                    if (useSharedBalance && sharedBalance != null) {
                        sharedBalance.updateUnrealizedPnL(entryValue, unrealizedPnL);
                    } else if (availableBalance != null && lockedBalance != null) {
                        totalBalance = availableBalance.add(lockedBalance).add(unrealizedPnL);
                    }
                }
            } else {
                // Нет позиции - проверяем состояние и ищем точку входа
                TradingStateMachine.TradingStateType state = stateMachine.getState(figi);
                
                // Если состояние ACTIVE или ENTRY_PENDING, но позиции нет - сбрасываем состояние
                if (state == TradingStateMachine.TradingStateType.ACTIVE || 
                    state == TradingStateMachine.TradingStateType.ENTRY_PENDING ||
                    state == TradingStateMachine.TradingStateType.EXIT_PENDING) {
                    logger.warn("Несоответствие: позиции нет, но состояние {} для {}. Сбрасываем в SCANNING.", state, figi);
                    stateMachine.resetToScanning(figi);
                    state = TradingStateMachine.TradingStateType.SCANNING;
                }
                
                // Проверяем коулдаун
                if (state == TradingStateMachine.TradingStateType.COOLDOWN) {
                    if (lastTradeTime != null) {
                        long minutesSinceTrade = ChronoUnit.MINUTES.between(lastTradeTime, currentTime);
                        if (minutesSinceTrade < COOLDOWN_MINUTES) {
                            continue; // Все еще в коулдауне
                        }
                    }
                    // Коулдаун истек, состояние автоматически обновлено в stateMachine.getState()
                    // Обновляем переменную state для дальнейших проверок
                    state = stateMachine.getState(figi);
                    // После истечения коулдауна состояние должно быть SCANNING
                    if (state != TradingStateMachine.TradingStateType.SCANNING) {
                        logger.warn("После истечения коулдауна состояние {} для {} не SCANNING. Принудительно сбрасываем.", state, figi);
                        stateMachine.resetToScanning(figi);
                        state = TradingStateMachine.TradingStateType.SCANNING;
                    }
                }
                
                // Проверяем, можно ли торговать (должно быть SCANNING)
                if (!stateMachine.canTrade(figi)) {
                    // Если не можем торговать, пропускаем эту итерацию
                    // Это может быть если состояние еще не SCANNING (например, после истечения коулдауна)
                    continue;
                }
                
                // Используем N8nSignalService для генерации сигнала
                // В бэктесте order_book и news не учитываются (null)
                TradeRequest tradeRequest = n8nSignalService.generateTradeSignal(
                    figi, ticker, indicatorsH1, indicatorsM15, null, null, currentPrice.doubleValue());
                
                // Проверяем, можно ли исполнять сигнал (confidence_score >= 0.8)
                if (!n8nSignalService.canExecuteSignal(tradeRequest)) {
                    continue;
                }
                
                String action = tradeRequest.getAction();
                if ("BUY".equalsIgnoreCase(action) || "SELL".equalsIgnoreCase(action)) {
                    // STATE MACHINE: Переход в ENTRY_PENDING
                    stateMachine.setEntryPending(figi, "backtest_" + currentTime.toEpochMilli());
                    
                    // Рассчитываем стоп-лосс и тейк-профит
                    double atr = indicatorsH1.getOrDefault("atr_14", 0.0);
                    if (atr == 0.0) {
                        stateMachine.resetToScanning(figi);
                        continue;
                    }
                    
                    BigDecimal stopLossOffset = BigDecimal.valueOf(atr).multiply(stopLossMultiplier);
                    BigDecimal takeProfitOffset = BigDecimal.valueOf(atr).multiply(takeProfitMultiplier);
                    
                    boolean isLong = "BUY".equalsIgnoreCase(action);
                    BigDecimal stopLossPrice = isLong 
                        ? currentPrice.subtract(stopLossOffset)
                        : currentPrice.add(stopLossOffset);
                    BigDecimal takeProfitPrice = isLong
                        ? currentPrice.add(takeProfitOffset)
                        : currentPrice.subtract(takeProfitOffset);
                    
                    // Используем RiskManagementService для расчета размера позиции
                    BigDecimal currentAvailableBalance = (useSharedBalance && sharedBalance != null)
                        ? sharedBalance.getAvailableBalance() 
                        : (availableBalance != null ? availableBalance : INITIAL_BALANCE);
                    long lotsToTrade = riskManagementService.calculateSafeLotSize(
                        currentAvailableBalance, currentPrice, stopLossPrice, instrument);
                    
                    if (lotsToTrade == 0) {
                        stateMachine.resetToScanning(figi);
                        continue;
                    }
                    
                    // ПРОВЕРКА ЭКОНОМИКИ (как в реальной торговле)
                    if (!isEconomicallyViable(ticker, currentPrice, takeProfitPrice, lotsToTrade, instrument.getLot())) {
                        stateMachine.resetToScanning(figi);
                        continue;
                    }
                    
                    // Проверка достаточности средств
                    BigDecimal tradeAmount = currentPrice.multiply(BigDecimal.valueOf(lotsToTrade * instrument.getLot()));
                    BigDecimal entryCommission = tradeAmount.multiply(ESTIMATED_COMMISSION_RATE);
                    
                    if (useSharedBalance && sharedBalance != null) {
                        // Используем синхронизированный общий баланс
                        if (!sharedBalance.openPosition(tradeAmount, entryCommission)) {
                            stateMachine.resetToScanning(figi);
                            continue; // Недостаточно средств
                        }
                    } else if (availableBalance != null && lockedBalance != null) {
                        // Локальный баланс для этого тикера
                        if (availableBalance.compareTo(tradeAmount.add(entryCommission)) < 0) {
                            stateMachine.resetToScanning(figi);
                            continue;
                        }
                        availableBalance = availableBalance.subtract(tradeAmount).subtract(entryCommission);
                        lockedBalance = lockedBalance.add(tradeAmount);
                        if (totalBalance != null) {
                            totalBalance = availableBalance.add(lockedBalance);
                        }
                    }
                    
                    // Открываем позицию
                    BigDecimal minPriceIncrement = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());
                    BigDecimal roundedSl = TinkoffApiUtils.roundToStep(stopLossPrice, minPriceIncrement);
                    BigDecimal roundedTp = TinkoffApiUtils.roundToStep(takeProfitPrice, minPriceIncrement);
                    
                    // В реальной торговле:
                    // - Средства блокируются (но остаются в балансе как заблокированные)
                    // - Комиссия за вход вычитается брокером автоматически при исполнении из availableBalance
                    // - entryPrice - это средняя цена входа БЕЗ комиссии (как в брокерском API)
                    // Для бэктеста используем цену закрытия свечи как цену исполнения
                    BigDecimal executionPrice = currentPrice; // В реальности может быть проскальзывание
                    
                    currentPosition = new Position(
                        currentTime,
                        executionPrice, // Цена входа БЕЗ комиссии (как в брокерском API)
                        lotsToTrade,
                        roundedSl,
                        roundedTp,
                        isLong
                    );
                    
                    // STATE MACHINE: Переход в ACTIVE
                    stateMachine.setActive(figi);
                }
            }
            
            // Обновляем пик и просадку на основе общего баланса (только для локального баланса)
            if (!useSharedBalance && totalBalance != null && peakBalance != null && maxDrawdown != null) {
                if (totalBalance.compareTo(peakBalance) > 0) {
                    peakBalance = totalBalance;
                }
                BigDecimal drawdown = peakBalance.subtract(totalBalance);
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                    if (maxDrawdownPercent != null && peakBalance.compareTo(BigDecimal.ZERO) > 0) {
                        maxDrawdownPercent = drawdown.divide(peakBalance, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));
                    }
                }
            }
        }
        
        // Закрываем последнюю позицию, если есть
        if (currentPosition != null) {
            HistoricCandle lastCandle = candlesM15.get(candlesM15.size() - 1);
            BigDecimal lastPrice = TinkoffApiUtils.quotationToBigDecimal(lastCandle.getClose());
            Instant lastTime = Instant.ofEpochSecond(lastCandle.getTime().getSeconds());
            
            BigDecimal quantity = BigDecimal.valueOf(currentPosition.lots * instrument.getLot());
            BigDecimal entryValue = currentPosition.entryPrice.multiply(quantity); // БЕЗ комиссии за вход
            BigDecimal exitValue = lastPrice.multiply(quantity);
            
            // Gross PnL
            BigDecimal grossPnL = currentPosition.isLong
                ? exitValue.subtract(entryValue)
                : entryValue.subtract(exitValue);
            
            // Комиссия за выход
            BigDecimal exitCommission = exitValue.multiply(ESTIMATED_COMMISSION_RATE);
            
            // Net PnL (комиссия за вход уже вычтена при открытии)
            BigDecimal netPnL = grossPnL.subtract(exitCommission);
            
            // Возвращаем заблокированные средства + PnL
            if (useSharedBalance && sharedBalance != null) {
                sharedBalance.closePosition(entryValue, netPnL);
            } else if (availableBalance != null && lockedBalance != null) {
                availableBalance = availableBalance.add(lockedBalance).add(netPnL);
                lockedBalance = BigDecimal.ZERO;
                if (totalBalance != null) {
                    totalBalance = availableBalance;
                }
            }
            
            // Для расчета процента используем стоимость входа + комиссия за вход
            BigDecimal entryValueWithCommission = entryValue.multiply(
                BigDecimal.ONE.add(ESTIMATED_COMMISSION_RATE));
            BigDecimal pnlPercent = netPnL.divide(entryValueWithCommission, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            
            trades.add(new Trade(
                currentPosition.entryTime,
                lastTime,
                currentPosition.entryPrice,
                lastPrice,
                quantity,
                netPnL,
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
        
        // Получаем финальные значения баланса
        BigDecimal finalMaxDrawdown;
        BigDecimal finalMaxDrawdownPercent;
        
        if (useSharedBalance && sharedBalance != null) {
            tickerFinalBalance = sharedBalance.getTotalBalance();
            finalMaxDrawdown = sharedBalance.getMaxDrawdown();
            finalMaxDrawdownPercent = sharedBalance.getMaxDrawdownPercent();
        } else {
            tickerFinalBalance = totalBalance != null ? totalBalance : tickerInitialBalance;
            finalMaxDrawdown = maxDrawdown != null ? maxDrawdown : BigDecimal.ZERO;
            finalMaxDrawdownPercent = maxDrawdownPercent != null ? maxDrawdownPercent : BigDecimal.ZERO;
        }
        
        return new BacktestResult(
            ticker,
            startDate,
            endDate,
            tickerInitialBalance,
            tickerFinalBalance,
            trades.size(),
            winningTrades,
            losingTrades,
            finalMaxDrawdown,
            finalMaxDrawdownPercent,
            trades
        );
    }
    
    /**
     * ПРОВЕРКА РЕНТАБЕЛЬНОСТИ (как в TinkoffOrderService)
     * Возвращает false, если комиссия съест прибыль.
     */
    private boolean isEconomicallyViable(String ticker, BigDecimal entryPrice, BigDecimal takeProfitPrice, long lots, int lotSize) {
        if (lots == 0) return false;

        BigDecimal volume = entryPrice.multiply(BigDecimal.valueOf(lots * lotSize));
        // Комиссия за круг (вход + выход)
        BigDecimal totalCommission = volume.multiply(ESTIMATED_COMMISSION_RATE).multiply(BigDecimal.valueOf(2));

        // Потенциал движения (грязная прибыль)
        BigDecimal potentialProfit = takeProfitPrice.subtract(entryPrice).abs().multiply(BigDecimal.valueOf(lots * lotSize));
        // Чистая прибыль (прогноз)
        BigDecimal netProfit = potentialProfit.subtract(totalCommission);

        // Соотношение Прибыль / Комиссия
        BigDecimal ratio = (totalCommission.compareTo(BigDecimal.ZERO) == 0) 
            ? BigDecimal.valueOf(100) 
            : potentialProfit.divide(totalCommission, 2, RoundingMode.HALF_UP);

        if (ratio.compareTo(MIN_PROFIT_TO_COMMISSION_RATIO) < 0) {
            return false;
        }

        if (netProfit.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        return true;
    }
    
    /**
     * Обновляет трейлинг стоп с безубытком (как в TinkoffOrderService)
     */
    private void updateTrailingStop(Position position, BigDecimal currentPrice, 
                                    Map<String, Double> indicators, Instrument instrument,
                                    BigDecimal stopLossMultiplier, BigDecimal breakevenMultiplier) {
        double atrValue = indicators.getOrDefault("atr_14", 0.0);
        if (atrValue == 0.0) return;
        
        BigDecimal minPriceIncrement = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());
        BigDecimal atrMultiplier = BigDecimal.valueOf(atrValue).multiply(breakevenMultiplier);
        
        if (position.isLong) {
            BigDecimal priceGain = currentPrice.subtract(position.entryPrice);
            
            // Если цена ушла в плюс на breakevenMultiplier * ATR, переносим стоп в безубыток
            if (priceGain.compareTo(atrMultiplier) >= 0) {
                BigDecimal breakevenStop = position.entryPrice.add(minPriceIncrement.multiply(BigDecimal.valueOf(5)));
                BigDecimal roundedBreakeven = TinkoffApiUtils.roundToStep(breakevenStop, minPriceIncrement);
                
                if (roundedBreakeven.compareTo(position.stopLoss) > 0) {
                    position.stopLoss = roundedBreakeven;
                }
            } else {
                // Обычный трейлинг: стоп на stopLossMultiplier * ATR от текущей цены
                BigDecimal newStopLossPrice = currentPrice.subtract(BigDecimal.valueOf(atrValue).multiply(stopLossMultiplier));
                BigDecimal roundedNewSl = TinkoffApiUtils.roundToStep(newStopLossPrice, minPriceIncrement);
                
                // Обновляем только если новый стоп выше старого и выше безубытка
                BigDecimal minStop = position.entryPrice.add(minPriceIncrement.multiply(BigDecimal.valueOf(5)));
                if (roundedNewSl.compareTo(minStop) > 0 && roundedNewSl.compareTo(position.stopLoss) > 0) {
                    position.stopLoss = roundedNewSl;
                }
            }
        } else {
            // SHORT позиция
            BigDecimal priceGain = position.entryPrice.subtract(currentPrice);
            
            // Если цена ушла в плюс на breakevenMultiplier * ATR, переносим стоп в безубыток
            if (priceGain.compareTo(atrMultiplier) >= 0) {
                BigDecimal breakevenStop = position.entryPrice.subtract(minPriceIncrement.multiply(BigDecimal.valueOf(5)));
                BigDecimal roundedBreakeven = TinkoffApiUtils.roundToStep(breakevenStop, minPriceIncrement);
                
                if (roundedBreakeven.compareTo(position.stopLoss) < 0) {
                    position.stopLoss = roundedBreakeven;
                }
            } else {
                // Обычный трейлинг: стоп на stopLossMultiplier * ATR от текущей цены
                BigDecimal newStopLossPrice = currentPrice.add(BigDecimal.valueOf(atrValue).multiply(stopLossMultiplier));
                BigDecimal roundedNewSl = TinkoffApiUtils.roundToStep(newStopLossPrice, minPriceIncrement);
                
                // Обновляем только если новый стоп ниже старого и ниже безубытка
                BigDecimal maxStop = position.entryPrice.subtract(minPriceIncrement.multiply(BigDecimal.valueOf(5)));
                if (roundedNewSl.compareTo(maxStop) < 0 && roundedNewSl.compareTo(position.stopLoss) < 0) {
                    position.stopLoss = roundedNewSl;
                }
            }
        }
    }
    
    private static class Position {
        final Instant entryTime;
        final BigDecimal entryPrice;
        final long lots;
        BigDecimal stopLoss; // Изменяемый для трейлинга
        final BigDecimal takeProfit;
        final boolean isLong;
        
        Position(Instant entryTime, BigDecimal entryPrice, long lots,
                BigDecimal stopLoss, BigDecimal takeProfit, boolean isLong) {
            this.entryTime = entryTime;
            this.entryPrice = entryPrice;
            this.lots = lots;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.isLong = isLong;
        }
    }
}
