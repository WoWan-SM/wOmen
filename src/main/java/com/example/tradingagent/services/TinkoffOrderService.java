package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApi;
import com.example.tradingagent.TinkoffApiUtils;
import com.example.tradingagent.dto.TradeRequest;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.contract.v1.GetOrderBookResponse;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Portfolio;
import ru.tinkoff.piapi.core.models.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Service
public class TinkoffOrderService {

    private final InvestApi api;
    private final String accountId;
    private final RiskManagementService riskManagementService;
    private final AuditService auditService;
    private final TradingStateMachine stateMachine;
    private final TelegramNotificationService telegramService;

    // --- НАСТРОЙКИ СТРАТЕГИИ (Улучшенные) ---
    // Динамический риск-менеджмент на основе ATR согласно roadmap:
    // Stop Loss = EntryPrice - (ATR * 2), Take Profit = EntryPrice + (ATR * 3)
    private static final BigDecimal ATR_MULTIPLIER_STOP_LOSS = new BigDecimal("3.0");
    private static final BigDecimal ATR_MULTIPLIER_TAKE_PROFIT = new BigDecimal("5.5");
    // Trailing Stop: Если цена ушла в плюс на 1 * ATR, переносим стоп в безубыток
    private static final BigDecimal ATR_MULTIPLIER_BREAKEVEN = new BigDecimal("0.5");

    // ГЛАВНЫЙ ФИЛЬТР: (Грязная Прибыль / Комиссия).
    // Если потенциальная прибыль меньше 3-х комиссий — сделка ОТМЕНЯЕТСЯ.
    private static final BigDecimal MIN_PROFIT_TO_COMMISSION_RATIO = new BigDecimal("3.0");

    // Ожидаемая комиссия брокера (0.05% - Трейдер, 0.3% - Инвестор).
    // ВАЖНО: Если у вас тариф "Инвестор", поменяйте на 0.003!
    private static final BigDecimal ESTIMATED_COMMISSION_RATE = new BigDecimal("0.0005");

    public TinkoffOrderService(TinkoffAccountService accountService, 
                               RiskManagementService riskManagementService,
                               AuditService auditService,
                               TradingStateMachine stateMachine,
                               TelegramNotificationService telegramService) {
        this.api = TinkoffApi.getApi();
        this.accountId = accountService.getSandboxAccountId();
        this.riskManagementService = riskManagementService;
        this.auditService = auditService;
        this.stateMachine = stateMachine;
        this.telegramService = telegramService;
    }

    private void log(String message) {
        System.out.println("[EXECUTION " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message);
    }

    private void logError(String message) {
        System.err.println("[EXECUTION " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] ERROR: " + message);
    }

    public void executeTrade(TradeRequest tradeRequest, Map<String, Double> indicators) {
        String requestedFigi = tradeRequest.getInstrumentFigi();
        Instrument instrument = api.getInstrumentsService().getInstrumentByFigiSync(requestedFigi);
        String ticker = instrument.getTicker();

        log(">>> АНАЛИЗ СИГНАЛА: " + tradeRequest.getAction() + " по " + ticker + " (Score: " + tradeRequest.getConfidenceScore() + ")");

        // Получаем портфель (Portfolio, а не Positions, чтобы была средняя цена)
        Portfolio portfolio = api.getOperationsService().getPortfolioSync(accountId);
        Optional<Position> existingPositionOpt = findPositionByFigi(portfolio, requestedFigi);

        if (existingPositionOpt.isPresent()) {
            Position position = existingPositionOpt.get();
            BigDecimal currentPrice = getRealTimePrice(requestedFigi);

            BigDecimal avgPrice = BigDecimal.ZERO;
            if (position.getAveragePositionPrice() != null) {
                avgPrice = position.getAveragePositionPrice().getValue();
            }

            if (avgPrice.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal pnlPercent = currentPrice.subtract(avgPrice)
                        .divide(avgPrice, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

                log(String.format("Позиция %s активна. Вход: %s, Тек: %s, PnL: %.2f%%", ticker, avgPrice, currentPrice, pnlPercent));
            }

            handleExistingPosition(position, tradeRequest, indicators, instrument);
        } else {
            if ("BUY".equalsIgnoreCase(tradeRequest.getAction())) {
                openNewPosition(tradeRequest, indicators, instrument, PositionType.LONG);
            } else if ("SELL".equalsIgnoreCase(tradeRequest.getAction())) {
                openNewPosition(tradeRequest, indicators, instrument, PositionType.SHORT);
            } else {
                log("Сигнал " + tradeRequest.getAction() + " пропущен (нет позиции).");
            }
        }
    }

    private enum PositionType { LONG, SHORT }

    private BigDecimal getRealTimePrice(String figi) {
        try {
            var lastPrices = api.getMarketDataService().getLastPricesSync(Collections.singletonList(figi));
            if (!lastPrices.isEmpty()) {
                return TinkoffApiUtils.quotationToBigDecimal(lastPrices.getFirst().getPrice());
            }
        } catch (Exception e) {
            logError("Сбой получения цены RealTime: " + e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    /**
     * ПРОВЕРКА РЕНТАБЕЛЬНОСТИ (МАТЕМАТИКА СДЕЛКИ)
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
        BigDecimal ratio = (totalCommission.compareTo(BigDecimal.ZERO) == 0) ? BigDecimal.valueOf(100) : potentialProfit.divide(totalCommission, 2, RoundingMode.HALF_UP);

        log(String.format("--- ЭКОНОМИКА СДЕЛКИ (%s) ---", ticker));
        log(String.format("Объем: %s руб | Комиссия (круг): ~%s руб", volume, totalCommission));
        log(String.format("Потенциал (ATR): %s руб | Чистая приб.: %s руб", potentialProfit, netProfit));
        log(String.format("Коэфф. P/C: %.2f (Требуется > %s)", ratio, MIN_PROFIT_TO_COMMISSION_RATIO));

        if (ratio.compareTo(MIN_PROFIT_TO_COMMISSION_RATIO) < 0) {
            logError("ОТМЕНА СДЕЛКИ: Нерентабельно. Комиссия слишком велика относительно ожидаемого профита.");
            return false;
        }

        if (netProfit.compareTo(BigDecimal.ZERO) <= 0) {
            logError("ОТМЕНА СДЕЛКИ: Математически убыточна (Net Profit <= 0).");
            return false;
        }

        return true;
    }

    private void handleExistingPosition(Position position, TradeRequest tradeRequest, Map<String, Double> indicators, Instrument instrument) {
        String ticker = instrument.getTicker();
        var activeStopOrders = api.getStopOrdersService().getStopOrdersSync(accountId);

        // 1. Миграция (удаление старых типов ордеров)
        Optional<StopOrder> legacyLimitSl = findStopOrder(activeStopOrders, position.getFigi(), StopOrderType.STOP_ORDER_TYPE_STOP_LIMIT);
        if (legacyLimitSl.isPresent()) {
            log("MIGRATION: Удаление устаревшего Stop-Limit...");
            try {
                api.getStopOrdersService().cancelStopOrderSync(accountId, legacyLimitSl.get().getStopOrderId());
                Thread.sleep(1000);
                activeStopOrders = api.getStopOrdersService().getStopOrdersSync(accountId);
            } catch (Exception e) { logError("Ошибка миграции: " + e.getMessage()); }
        }

        Optional<StopOrder> existingSl = findStopOrder(activeStopOrders, position.getFigi(), StopOrderType.STOP_ORDER_TYPE_STOP_LOSS);
        Optional<StopOrder> existingTp = findStopOrder(activeStopOrders, position.getFigi(), StopOrderType.STOP_ORDER_TYPE_TAKE_PROFIT);

        if (existingTp.isEmpty()) log("WARN: У позиции нет Take Profit!");
        if (existingSl.isEmpty()) log("WARN: У позиции нет Stop Loss!");

        BigDecimal realTimePrice = getRealTimePrice(position.getFigi());
        if (realTimePrice.compareTo(BigDecimal.ZERO) == 0) {
            realTimePrice = BigDecimal.valueOf(indicators.getOrDefault("current_price", 0.0));
        }

        double atrValue = indicators.getOrDefault("atr_14", 0.0);
        if (realTimePrice.compareTo(BigDecimal.ZERO) == 0 || atrValue == 0.0) return;

        BigDecimal minPriceIncrement = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());

        // 2. Восстановление ордеров
        if (existingSl.isEmpty() || existingTp.isEmpty()) {
            log("ЗАПУСК ВОССТАНОВЛЕНИЯ ОРДЕРОВ...");
            // Исправленное получение количества через toBigInteger()
            long quantityInLots = position.getQuantity().toBigInteger().longValue() / instrument.getLot();

            if (quantityInLots == 0) {
                logError("Баланс < 1 лота. Ордера невозможны.");
                return;
            }

            long lotsAbs = Math.abs(quantityInLots);
            boolean isLong = quantityInLots > 0;

            BigDecimal stopLossOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS);
            BigDecimal takeProfitOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_TAKE_PROFIT);

            BigDecimal stopLossPrice = isLong ? realTimePrice.subtract(stopLossOffset) : realTimePrice.add(stopLossOffset);
            BigDecimal takeProfitPrice = isLong ? realTimePrice.add(takeProfitOffset) : realTimePrice.subtract(takeProfitOffset);

            StopOrderDirection closeDirection = isLong ? StopOrderDirection.STOP_ORDER_DIRECTION_SELL : StopOrderDirection.STOP_ORDER_DIRECTION_BUY;

            if (existingSl.isEmpty()) {
                BigDecimal roundedSl = TinkoffApiUtils.roundToStep(stopLossPrice, minPriceIncrement);
                postMarketStopLossOrder(position.getFigi(), lotsAbs, roundedSl, closeDirection, ticker);
            }

            if (existingTp.isEmpty()) {
                BigDecimal roundedTp = TinkoffApiUtils.roundToStep(takeProfitPrice, minPriceIncrement);
                BigDecimal slippage = minPriceIncrement.multiply(BigDecimal.valueOf(20));
                BigDecimal executionPrice = isLong ? roundedTp.subtract(slippage) : roundedTp.add(slippage);

                postLimitTakeProfitOrder(position.getFigi(), lotsAbs, roundedTp, TinkoffApiUtils.roundToStep(executionPrice, minPriceIncrement), closeDirection, ticker);
            }
            return;
        }

        // 3. Динамический Трейлинг Стоп с безубытком
        StopOrder stopOrder = existingSl.get();
        BigDecimal oldStopLossPrice = TinkoffApiUtils.moneyValueToBigDecimal(stopOrder.getStopPrice());
        BigDecimal atrMultiplier = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_BREAKEVEN);
        
        // Получаем среднюю цену входа позиции
        BigDecimal avgPrice = BigDecimal.ZERO;
        if (position.getAveragePositionPrice() != null) {
            avgPrice = position.getAveragePositionPrice().getValue();
        }

        if (stopOrder.getDirection() == StopOrderDirection.STOP_ORDER_DIRECTION_SELL) { // LONG
            BigDecimal priceGain = realTimePrice.subtract(avgPrice);
            
            // Если цена ушла в плюс на 1 * ATR, переносим стоп в безубыток (BREAKEVEN)
            if (priceGain.compareTo(atrMultiplier) >= 0 && avgPrice.compareTo(BigDecimal.ZERO) > 0) {
                // Переносим стоп в безубыток (с небольшим отступом)
                BigDecimal breakevenStop = avgPrice.add(minPriceIncrement.multiply(BigDecimal.valueOf(5))); // +5 шагов для защиты
                BigDecimal roundedBreakeven = TinkoffApiUtils.roundToStep(breakevenStop, minPriceIncrement);
                
                if (roundedBreakeven.compareTo(oldStopLossPrice) > 0) {
                    log(String.format("TRAILING TO BREAKEVEN LONG: %s -> %s (Цена: %s, Entry: %s)", 
                        oldStopLossPrice, roundedBreakeven, realTimePrice, avgPrice));
                    updateStopLossOrder(stopOrder, roundedBreakeven, instrument);
                }
            } else {
                // Обычный трейлинг: стоп на ATR * 2 от текущей цены
                BigDecimal newStopLossPrice = realTimePrice.subtract(BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS));
                BigDecimal roundedNewSl = TinkoffApiUtils.roundToStep(newStopLossPrice, minPriceIncrement);
                
                // Обновляем только если новый стоп выше старого и выше безубытка
                BigDecimal minStop = avgPrice.add(minPriceIncrement.multiply(BigDecimal.valueOf(5)));
                if (roundedNewSl.compareTo(minStop) > 0 && roundedNewSl.compareTo(oldStopLossPrice) > 0) {
                    log(String.format("TRAILING LONG: %s -> %s (Цена: %s)", oldStopLossPrice, roundedNewSl, realTimePrice));
                    updateStopLossOrder(stopOrder, roundedNewSl, instrument);
                }
            }
        } else { // SHORT
            BigDecimal priceGain = avgPrice.subtract(realTimePrice);
            
            // Если цена ушла в плюс на 1 * ATR, переносим стоп в безубыток
            if (priceGain.compareTo(atrMultiplier) >= 0 && avgPrice.compareTo(BigDecimal.ZERO) > 0) {
                // Переносим стоп в безубыток (с небольшим отступом)
                BigDecimal breakevenStop = avgPrice.subtract(minPriceIncrement.multiply(BigDecimal.valueOf(5))); // -5 шагов для защиты
                BigDecimal roundedBreakeven = TinkoffApiUtils.roundToStep(breakevenStop, minPriceIncrement);
                
                if (roundedBreakeven.compareTo(oldStopLossPrice) < 0) {
                    log(String.format("TRAILING TO BREAKEVEN SHORT: %s -> %s (Цена: %s, Entry: %s)", 
                        oldStopLossPrice, roundedBreakeven, realTimePrice, avgPrice));
                    updateStopLossOrder(stopOrder, roundedBreakeven, instrument);
                }
            } else {
                // Обычный трейлинг: стоп на ATR * 2 от текущей цены
                BigDecimal newStopLossPrice = realTimePrice.add(BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS));
                BigDecimal roundedNewSl = TinkoffApiUtils.roundToStep(newStopLossPrice, minPriceIncrement);
                
                // Обновляем только если новый стоп ниже старого и ниже безубытка
                BigDecimal maxStop = avgPrice.subtract(minPriceIncrement.multiply(BigDecimal.valueOf(5)));
                if (roundedNewSl.compareTo(maxStop) < 0 && roundedNewSl.compareTo(oldStopLossPrice) < 0) {
                    log(String.format("TRAILING SHORT: %s -> %s (Цена: %s)", oldStopLossPrice, roundedNewSl, realTimePrice));
                    updateStopLossOrder(stopOrder, roundedNewSl, instrument);
                }
            }
        }
    }

    private void updateStopLossOrder(StopOrder existingStopOrder, BigDecimal newStopLossPrice, Instrument instrument) {
        try {
            api.getStopOrdersService().cancelStopOrderSync(accountId, existingStopOrder.getStopOrderId());
            Thread.sleep(1000);
            postMarketStopLossOrder(existingStopOrder.getFigi(), existingStopOrder.getLotsRequested(), newStopLossPrice, existingStopOrder.getDirection(), instrument.getTicker());
        } catch (Exception e) {
            logError("Ошибка обновления SL: " + e.getMessage());
        }
    }

    private void openNewPosition(TradeRequest tradeRequest, Map<String, Double> indicators, Instrument instrument, PositionType positionType) {
        String ticker = instrument.getTicker();
        log("--- ПОПЫТКА ВХОДА: " + ticker + " (" + positionType + ") ---");

        try {
            // ПРОВЕРКА БАЛАНСА ПЕРЕД ОРДЕРОМ (Pre-flight Check)
            var marginAttributes = api.getUserService().getMarginAttributesSync(accountId);
            BigDecimal availableMargin = TinkoffApiUtils.moneyValueToBigDecimal(marginAttributes.getLiquidPortfolio())
                    .subtract(TinkoffApiUtils.moneyValueToBigDecimal(marginAttributes.getStartingMargin()));

            BigDecimal currentPrice = getRealTimePrice(instrument.getFigi());
            if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
                currentPrice = BigDecimal.valueOf(indicators.getOrDefault("current_price", 0.0));
            }

            double atrValue = indicators.getOrDefault("atr_14", 0.0);
            if (atrValue == 0.0) {
                logError("ОТКАЗ: ATR = 0.");
                return;
            }

            // ФИЛЬТР СПРЕДА: Если спред > 0.05%, комиссия съест прибыль
            try {
                GetOrderBookResponse orderBook = api.getMarketDataService().getOrderBookSync(instrument.getFigi(), 10);
                if (!orderBook.getBidsList().isEmpty() && !orderBook.getAsksList().isEmpty()) {
                    BigDecimal bestBid = TinkoffApiUtils.quotationToBigDecimal(orderBook.getBidsList().get(0).getPrice());
                    BigDecimal bestAsk = TinkoffApiUtils.quotationToBigDecimal(orderBook.getAsksList().get(0).getPrice());
                    BigDecimal spread = bestAsk.subtract(bestBid);
                    BigDecimal spreadPercent = spread.divide(bestBid, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    
                    BigDecimal MAX_SPREAD_PERCENT = new BigDecimal("0.05"); // 0.05%
                    if (spreadPercent.compareTo(MAX_SPREAD_PERCENT) > 0) {
                        logError(String.format("ОТКАЗ: Большой спред %.4f%% > %.4f%% для %s (комиссия съест прибыль)", 
                            spreadPercent, MAX_SPREAD_PERCENT, ticker));
                        return;
                    }
                    log(String.format("Спред для %s: %.4f%% (OK)", ticker, spreadPercent));
                }
            } catch (Exception e) {
                logError("Ошибка проверки спреда для " + ticker + ": " + e.getMessage());
                // Продолжаем, если не удалось получить спред
            }

            BigDecimal stopLossOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS);
            BigDecimal stopLossPrice = (positionType == PositionType.LONG) ? currentPrice.subtract(stopLossOffset) : currentPrice.add(stopLossOffset);

            long lotsToTrade = riskManagementService.calculateSafeLotSize(availableMargin, currentPrice, stopLossPrice, instrument);

            if (lotsToTrade == 0) {
                logError("ОТКАЗ: Risk-менеджер (lots=0).");
                telegramService.notifyRejection(ticker, "Risk-менеджер вернул 0 лотов");
                return;
            }

            // ПРОВЕРКА СВОБОДНЫХ СРЕДСТВ (Pre-flight Check)
            BigDecimal tradeAmount = currentPrice.multiply(BigDecimal.valueOf(lotsToTrade * instrument.getLot()));
            if (availableMargin.compareTo(tradeAmount) < 0) {
                String errorMsg = String.format("Недостаточно средств. Требуется: %s руб, Доступно: %s руб", 
                    tradeAmount, availableMargin);
                logError("ОТКАЗ: " + errorMsg);
                telegramService.notifyError(errorMsg);
                return;
            }

            // ПРОВЕРКА ЭКОНОМИКИ
            BigDecimal takeProfitOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_TAKE_PROFIT);
            BigDecimal takeProfitPrice = (positionType == PositionType.LONG) ? currentPrice.add(takeProfitOffset) : currentPrice.subtract(takeProfitOffset);

            if (!isEconomicallyViable(ticker, currentPrice, takeProfitPrice, lotsToTrade, instrument.getLot())) {
                return;
            }

            String orderId = UUID.randomUUID().toString();
            OrderDirection orderDirection = (positionType == PositionType.LONG) ? OrderDirection.ORDER_DIRECTION_BUY : OrderDirection.ORDER_DIRECTION_SELL;

            log("Отправка рыночного ордера...");
            
            // STATE MACHINE: Переход в ENTRY_PENDING
            stateMachine.setEntryPending(instrument.getFigi(), orderId);
            
            // ЛОГИРОВАНИЕ: Сохраняем ордер
            com.example.tradingagent.entities.OrderEntity.Direction direction = 
                (positionType == PositionType.LONG) 
                    ? com.example.tradingagent.entities.OrderEntity.Direction.BUY 
                    : com.example.tradingagent.entities.OrderEntity.Direction.SELL;
            auditService.saveOrder(ticker, direction, currentPrice, lotsToTrade, orderId);
            
            try {
                api.getOrdersService().postOrderSync(
                        instrument.getFigi(), lotsToTrade, Quotation.newBuilder().build(), orderDirection,
                        accountId, OrderType.ORDER_TYPE_MARKET, orderId
                );
            } catch (Exception e) {
                logError("ОШИБКА ОТПРАВКИ ОРДЕРА: " + e.getMessage());
                // ЛОГИРОВАНИЕ: Обновляем статус ордера как REJECTED
                auditService.updateOrderStatus(orderId, 
                    com.example.tradingagent.entities.OrderEntity.OrderStatus.REJECTED,
                    null, null, e.getMessage());
                // STATE MACHINE: Возврат к SCANNING
                stateMachine.resetToScanning(instrument.getFigi());
                // TELEGRAM: Уведомление об ошибке
                telegramService.notifyError("Ошибка отправки ордера для " + ticker + ": " + e.getMessage());
                return;
            }

            log("Ордер отправлен. Ожидание позиции...");
            boolean positionOpened = waitForPosition(instrument.getFigi(), 15, 1000);

            if (positionOpened) {
                log("ПОЗИЦИЯ ОТКРЫТА. Выставляем стопы.");
                
                // STATE MACHINE: Переход в ACTIVE
                stateMachine.setActive(instrument.getFigi());
                
                // ЛОГИРОВАНИЕ: Обновляем статус ордера
                auditService.updateOrderStatus(orderId, 
                    com.example.tradingagent.entities.OrderEntity.OrderStatus.FILLED,
                    currentPrice, null, null);
                
                // ЛОГИРОВАНИЕ: Создаем позицию для отслеживания PnL
                var savedOrderOpt = auditService.getOrderRepository().findByBrokerOrderId(orderId);
                if (savedOrderOpt.isPresent()) {
                    auditService.createPosition(savedOrderOpt.get(), currentPrice);
                    log("Позиция создана в БД для отслеживания PnL");
                }
                
                // TELEGRAM: Уведомление о покупке
                telegramService.notifyTrade(tradeRequest.getAction(), ticker, currentPrice, lotsToTrade);
                
                StopOrderDirection stopDir = (positionType == PositionType.LONG) ? StopOrderDirection.STOP_ORDER_DIRECTION_SELL : StopOrderDirection.STOP_ORDER_DIRECTION_BUY;
                BigDecimal minStep = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());

                BigDecimal roundedSl = TinkoffApiUtils.roundToStep(stopLossPrice, minStep);
                BigDecimal roundedTp = TinkoffApiUtils.roundToStep(takeProfitPrice, minStep);
                
                postMarketStopLossOrder(instrument.getFigi(), lotsToTrade, roundedSl, stopDir, ticker);

                BigDecimal slippage = minStep.multiply(BigDecimal.valueOf(20));
                BigDecimal executionPrice = (positionType == PositionType.LONG) ? roundedTp.subtract(slippage) : roundedTp.add(slippage);

                postLimitTakeProfitOrder(instrument.getFigi(), lotsToTrade, roundedTp, TinkoffApiUtils.roundToStep(executionPrice, minStep), stopDir, ticker);
                
                // TELEGRAM: Уведомление об открытии позиции со стопами
                telegramService.notifyPositionOpened(ticker, currentPrice, roundedSl, roundedTp);
            } else {
                logError("CRITICAL: Позиция не появилась на балансе!");
                // STATE MACHINE: Возврат к SCANNING
                stateMachine.resetToScanning(instrument.getFigi());
                // ЛОГИРОВАНИЕ: Обновляем статус ордера как REJECTED
                auditService.updateOrderStatus(orderId, 
                    com.example.tradingagent.entities.OrderEntity.OrderStatus.REJECTED,
                    null, null, "Позиция не появилась на балансе");
            }

        } catch (Exception e) {
            logError("Исключение при открытии: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void postMarketStopLossOrder(String figi, long quantity, BigDecimal stopPrice, StopOrderDirection direction, String ticker) {
        if (quantity <= 0) return;
        int maxRetries = 10;
        for (int i = 0; i < maxRetries; i++) {
            try {
                Quotation marketPrice = Quotation.newBuilder().setUnits(0).setNano(0).build();
                api.getStopOrdersService().postStopOrderGoodTillCancelSync(
                        figi, quantity, marketPrice, TinkoffApiUtils.bigDecimalToQuotation(stopPrice),
                        direction, accountId, StopOrderType.STOP_ORDER_TYPE_STOP_LOSS
                );
                log("SL установлен: " + ticker + " @ " + stopPrice);
                return;
            } catch (Exception e) {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            }
        }
        logError("FAIL: Не удалось выставить SL для " + ticker);
    }

    private void postLimitTakeProfitOrder(String figi, long quantity, BigDecimal triggerPrice, BigDecimal limitPrice, StopOrderDirection direction, String ticker) {
        if (quantity <= 0) return;
        int maxRetries = 10;
        for (int i = 0; i < maxRetries; i++) {
            try {
                api.getStopOrdersService().postStopOrderGoodTillCancelSync(
                        figi, quantity, TinkoffApiUtils.bigDecimalToQuotation(limitPrice),
                        TinkoffApiUtils.bigDecimalToQuotation(triggerPrice), direction, accountId,
                        StopOrderType.STOP_ORDER_TYPE_TAKE_PROFIT
                );
                log("TP установлен: " + ticker + " Trigger: " + triggerPrice);
                return;
            } catch (Exception e) {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            }
        }
        logError("FAIL: Не удалось выставить TP для " + ticker);
    }

    private boolean waitForPosition(String figi, int maxRetries, long delayMillis) throws InterruptedException {
        for (int i = 0; i < maxRetries; i++) {
            var portfolio = api.getOperationsService().getPortfolioSync(accountId);
            if (findPositionByFigi(portfolio, figi).isPresent()) return true;
            TimeUnit.MILLISECONDS.sleep(delayMillis);
        }
        return false;
    }

    private Optional<Position> findPositionByFigi(Portfolio portfolio, String figi) {
        return portfolio.getPositions().stream().filter(p -> p.getFigi().equals(figi) && p.getQuantity().compareTo(BigDecimal.ZERO) != 0).findFirst();
    }

    private Optional<StopOrder> findStopOrder(List<StopOrder> orders, String figi, StopOrderType type) {
        return orders.stream().filter(o -> o.getFigi().equals(figi) && o.getOrderType() == type).findFirst();
    }
}