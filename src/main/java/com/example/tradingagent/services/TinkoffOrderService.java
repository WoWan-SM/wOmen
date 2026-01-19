package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApi;
import com.example.tradingagent.TinkoffApiUtils;
import com.example.tradingagent.dto.TradeRequest;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Positions;
import ru.tinkoff.piapi.core.models.SecurityPosition;


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

    // Коэффициенты
    private static final BigDecimal ATR_MULTIPLIER_STOP_LOSS = new BigDecimal("3.5");
    private static final BigDecimal ATR_MULTIPLIER_TAKE_PROFIT = new BigDecimal("7.0");

    // Примерная комиссия брокера (для логов). 0.05% за сделку (Трейдер).
    // Если у вас тариф Инвестор, ставьте 0.3 (0.003). Это КРИТИЧНО для понимания убытков.
    private static final BigDecimal ESTIMATED_COMMISSION_RATE = new BigDecimal("0.0005");

    public TinkoffOrderService(TinkoffAccountService accountService, RiskManagementService riskManagementService) {
        this.api = TinkoffApi.getApi();
        this.accountId = accountService.getSandboxAccountId();
        this.riskManagementService = riskManagementService;
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message);
    }

    private void logError(String message) {
        System.err.println("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] ERROR: " + message);
    }

    public void executeTrade(TradeRequest tradeRequest, Map<String, Double> indicators) {
        String requestedFigi = tradeRequest.getInstrumentFigi();
        Instrument instrument = api.getInstrumentsService().getInstrumentByFigiSync(requestedFigi);
        String ticker = instrument.getTicker();

        log(">>> ОБРАБОТКА СИГНАЛА: " + tradeRequest.getAction() + " " + ticker);

        Positions positions = api.getOperationsService().getPositionsSync(accountId);
        Optional<SecurityPosition> existingPositionOpt = findPositionByFigi(positions, requestedFigi);

        if (existingPositionOpt.isPresent()) {
            log("Позиция " + ticker + " существует. Проверка защиты.");
            handleExistingPosition(existingPositionOpt.get(), tradeRequest, indicators, instrument);
        } else {
            if ("BUY".equalsIgnoreCase(tradeRequest.getAction())) {
                openNewPosition(tradeRequest, indicators, instrument, PositionType.LONG);
            } else if ("SELL".equalsIgnoreCase(tradeRequest.getAction())) {
                openNewPosition(tradeRequest, indicators, instrument, PositionType.SHORT);
            } else {
                log("Действие " + tradeRequest.getAction() + " пропущено (нет позиции).");
            }
        }
    }

    private enum PositionType { LONG, SHORT }

    private BigDecimal getRealTimePrice(String figi) {
        try {
            var lastPrices = api.getMarketDataService().getLastPricesSync(Collections.singletonList(figi));
            if (!lastPrices.isEmpty()) {
                return TinkoffApiUtils.quotationToBigDecimal(lastPrices.get(0).getPrice());
            }
        } catch (Exception e) {
            logError("Нет цены RealTime для " + figi);
        }
        return BigDecimal.ZERO;
    }

    // НОВЫЙ МЕТОД: Анализ экономики сделки перед входом
    private void logTradeEconomics(String ticker, BigDecimal currentPrice, BigDecimal stopLoss, BigDecimal takeProfit, long lots, int lotSize) {
        BigDecimal entryTotal = currentPrice.multiply(BigDecimal.valueOf(lots * lotSize));
        BigDecimal commissionEntry = entryTotal.multiply(ESTIMATED_COMMISSION_RATE);

        // Потенциальный убыток
        BigDecimal lossPerShare = currentPrice.subtract(stopLoss).abs();
        BigDecimal totalLoss = lossPerShare.multiply(BigDecimal.valueOf(lots * lotSize)).add(commissionEntry).add(commissionEntry); // Комиссия вход + выход

        // Потенциальная прибыль
        BigDecimal profitPerShare = currentPrice.subtract(takeProfit).abs();
        BigDecimal totalProfit = profitPerShare.multiply(BigDecimal.valueOf(lots * lotSize)).subtract(commissionEntry).subtract(commissionEntry);

        BigDecimal riskRewardRatio = (totalLoss.compareTo(BigDecimal.ZERO) != 0) ? totalProfit.divide(totalLoss, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        log(String.format("--- ЭКОНОМИКА СДЕЛКИ %s ---", ticker));
        log(String.format("Цена входа: %s | SL: %s | TP: %s", currentPrice, stopLoss, takeProfit));
        log(String.format("Комиссия (прогноз): %s руб (x2 = %s руб за круг)", commissionEntry, commissionEntry.multiply(new BigDecimal("2"))));
        log(String.format("Риск (с комиссией): -%s руб", totalLoss));
        log(String.format("Профит (чистый): +%s руб", totalProfit));
        log(String.format("Risk/Reward: 1 к %s", riskRewardRatio));

        if (totalProfit.compareTo(BigDecimal.ZERO) <= 0) {
            logError("ВНИМАНИЕ: Сделка математически убыточна! Комиссии съедают весь профит.");
        }
    }

    private void handleExistingPosition(SecurityPosition position, TradeRequest tradeRequest, Map<String, Double> indicators, Instrument instrument) {
        String ticker = instrument.getTicker();
        var activeStopOrders = api.getStopOrdersService().getStopOrdersSync(accountId);

        Optional<StopOrder> existingSl = findStopOrder(activeStopOrders, position.getFigi(), StopOrderType.STOP_ORDER_TYPE_STOP_LOSS);
        Optional<StopOrder> existingTp = findStopOrder(activeStopOrders, position.getFigi(), StopOrderType.STOP_ORDER_TYPE_TAKE_PROFIT);

        BigDecimal realTimePrice = getRealTimePrice(position.getFigi());
        if (realTimePrice.compareTo(BigDecimal.ZERO) == 0) {
            realTimePrice = BigDecimal.valueOf(indicators.getOrDefault("current_price", 0.0));
        }

        double atrValue = indicators.getOrDefault("atr_14", 0.0);
        if (realTimePrice.compareTo(BigDecimal.ZERO) == 0 || atrValue == 0.0) return;

        BigDecimal minPriceIncrement = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());

        // Восстановление
        if (existingSl.isEmpty() || existingTp.isEmpty()) {
            log(String.format("ВОССТАНОВЛЕНИЕ %s. SL: %b, TP: %b", ticker, existingSl.isPresent(), existingTp.isPresent()));

            long quantityInLots = position.getBalance() / instrument.getLot();
            if (quantityInLots == 0) return;
            long lotsAbs = Math.abs(quantityInLots);
            boolean isLong = quantityInLots > 0;

            BigDecimal stopLossOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS);
            BigDecimal takeProfitOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_TAKE_PROFIT);

            BigDecimal stopLossPrice = isLong ? realTimePrice.subtract(stopLossOffset) : realTimePrice.add(stopLossOffset);
            BigDecimal takeProfitPrice = isLong ? realTimePrice.add(takeProfitOffset) : realTimePrice.subtract(takeProfitOffset);

            // Логируем экономику перед выставлением восстановления
            logTradeEconomics(ticker, realTimePrice, stopLossPrice, takeProfitPrice, lotsAbs, instrument.getLot());

            StopOrderDirection closeDirection = isLong ? StopOrderDirection.STOP_ORDER_DIRECTION_SELL : StopOrderDirection.STOP_ORDER_DIRECTION_BUY;

            if (existingSl.isEmpty()) {
                postMarketStopLossOrder(position.getFigi(), lotsAbs, TinkoffApiUtils.roundToStep(stopLossPrice, minPriceIncrement), closeDirection, ticker);
            }
            if (existingTp.isEmpty()) {
                BigDecimal roundedTp = TinkoffApiUtils.roundToStep(takeProfitPrice, minPriceIncrement);
                BigDecimal slippage = minPriceIncrement.multiply(BigDecimal.valueOf(20));
                BigDecimal executionPrice = isLong ? roundedTp.subtract(slippage) : roundedTp.add(slippage);
                postLimitTakeProfitOrder(position.getFigi(), lotsAbs, roundedTp, TinkoffApiUtils.roundToStep(executionPrice, minPriceIncrement), closeDirection, ticker);
            }
            return;
        }

        // Трейлинг
        StopOrder stopOrder = existingSl.get();
        BigDecimal oldStopLossPrice = TinkoffApiUtils.moneyValueToBigDecimal(stopOrder.getStopPrice());
        BigDecimal updateThreshold = realTimePrice.multiply(new BigDecimal("0.005"));

        if (stopOrder.getDirection() == StopOrderDirection.STOP_ORDER_DIRECTION_SELL) { // LONG
            BigDecimal newStopLossPrice = realTimePrice.subtract(BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS));
            BigDecimal roundedNewSl = TinkoffApiUtils.roundToStep(newStopLossPrice, minPriceIncrement);

            if (roundedNewSl.compareTo(oldStopLossPrice.add(updateThreshold)) > 0) {
                log(String.format("Трейлинг LONG %s: %s -> %s", ticker, oldStopLossPrice, roundedNewSl));
                updateStopLossOrder(stopOrder, roundedNewSl, instrument);
            }
        } else { // SHORT
            BigDecimal newStopLossPrice = realTimePrice.add(BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS));
            BigDecimal roundedNewSl = TinkoffApiUtils.roundToStep(newStopLossPrice, minPriceIncrement);

            if (roundedNewSl.compareTo(oldStopLossPrice.subtract(updateThreshold)) < 0) {
                log(String.format("Трейлинг SHORT %s: %s -> %s", ticker, oldStopLossPrice, roundedNewSl));
                updateStopLossOrder(stopOrder, roundedNewSl, instrument);
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
        log(">>> ПОПЫТКА ВХОДА: " + ticker + " (" + positionType + ")");

        try {
            var marginAttributes = api.getUserService().getMarginAttributesSync(accountId);
            BigDecimal liquidPortfolio = TinkoffApiUtils.moneyValueToBigDecimal(marginAttributes.getLiquidPortfolio());
            BigDecimal availableMargin = liquidPortfolio.subtract(TinkoffApiUtils.moneyValueToBigDecimal(marginAttributes.getStartingMargin()));

            BigDecimal currentPrice = getRealTimePrice(instrument.getFigi());
            if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
                currentPrice = BigDecimal.valueOf(indicators.getOrDefault("current_price", 0.0));
            }

            double atrValue = indicators.getOrDefault("atr_14", 0.0);
            if (atrValue == 0.0) return;

            BigDecimal stopLossOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS);
            BigDecimal stopLossPrice = (positionType == PositionType.LONG) ? currentPrice.subtract(stopLossOffset) : currentPrice.add(stopLossOffset);

            long lotsToTrade = riskManagementService.calculateSafeLotSize(availableMargin, currentPrice, stopLossPrice, instrument);

            // ЛОГИРУЕМ ЭКОНОМИКУ
            BigDecimal takeProfitOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_TAKE_PROFIT);
            BigDecimal takeProfitPrice = (positionType == PositionType.LONG) ? currentPrice.add(takeProfitOffset) : currentPrice.subtract(takeProfitOffset);
            logTradeEconomics(ticker, currentPrice, stopLossPrice, takeProfitPrice, lotsToTrade, instrument.getLot());

            if (lotsToTrade == 0) {
                log("ОТКАЗ: RiskManagement (0 лотов).");
                return;
            }

            String orderId = UUID.randomUUID().toString();
            OrderDirection orderDirection = (positionType == PositionType.LONG) ? OrderDirection.ORDER_DIRECTION_BUY : OrderDirection.ORDER_DIRECTION_SELL;

            api.getOrdersService().postOrderSync(
                    instrument.getFigi(), lotsToTrade, Quotation.newBuilder().build(), orderDirection,
                    accountId, OrderType.ORDER_TYPE_MARKET, orderId
            );

            log("Ордер отправлен. Ждем позицию...");
            boolean positionOpened = waitForPosition(instrument.getFigi(), 15, 1000);

            if (positionOpened) {
                log("ПОЗИЦИЯ ОТКРЫТА. Выставляем стопы.");
                StopOrderDirection stopDir = (positionType == PositionType.LONG) ? StopOrderDirection.STOP_ORDER_DIRECTION_SELL : StopOrderDirection.STOP_ORDER_DIRECTION_BUY;
                BigDecimal minStep = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());

                postMarketStopLossOrder(instrument.getFigi(), lotsToTrade, TinkoffApiUtils.roundToStep(stopLossPrice, minStep), stopDir, ticker);

                BigDecimal roundedTp = TinkoffApiUtils.roundToStep(takeProfitPrice, minStep);
                BigDecimal slippage = minStep.multiply(BigDecimal.valueOf(20));
                BigDecimal executionPrice = (positionType == PositionType.LONG) ? roundedTp.subtract(slippage) : roundedTp.add(slippage);
                postLimitTakeProfitOrder(instrument.getFigi(), lotsToTrade, roundedTp, TinkoffApiUtils.roundToStep(executionPrice, minStep), stopDir, ticker);
            } else {
                logError("ПОЗИЦИЯ НЕ НАЙДЕНА после ордера!");
            }

        } catch (Exception e) {
            logError("Ошибка входа: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void postMarketStopLossOrder(String figi, long quantity, BigDecimal stopPrice, StopOrderDirection direction, String ticker) {
        if (quantity <= 0) return;
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                Quotation marketPrice = Quotation.newBuilder().setUnits(0).setNano(0).build();
                api.getStopOrdersService().postStopOrderGoodTillCancelSync(
                        figi, quantity, marketPrice, TinkoffApiUtils.bigDecimalToQuotation(stopPrice),
                        direction, accountId, StopOrderType.STOP_ORDER_TYPE_STOP_LOSS
                );
                log("SL выставлен: " + ticker + " @ " + stopPrice);
                return;
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        logError("FAIL: Не удалось выставить SL для " + ticker);
    }

    private void postLimitTakeProfitOrder(String figi, long quantity, BigDecimal triggerPrice, BigDecimal limitPrice, StopOrderDirection direction, String ticker) {
        if (quantity <= 0) return;
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                api.getStopOrdersService().postStopOrderGoodTillCancelSync(
                        figi, quantity, TinkoffApiUtils.bigDecimalToQuotation(limitPrice),
                        TinkoffApiUtils.bigDecimalToQuotation(triggerPrice), direction, accountId,
                        StopOrderType.STOP_ORDER_TYPE_TAKE_PROFIT
                );
                log("TP выставлен: " + ticker + " (Trigger: " + triggerPrice + ")");
                return;
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        logError("FAIL: Не удалось выставить TP для " + ticker);
    }

    private boolean waitForPosition(String figi, int maxRetries, long delayMillis) throws InterruptedException {
        for (int i = 0; i < maxRetries; i++) {
            var positions = api.getOperationsService().getPositionsSync(accountId);
            if (findPositionByFigi(positions, figi).isPresent()) return true;
            TimeUnit.MILLISECONDS.sleep(delayMillis);
        }
        return false;
    }

    private Optional<SecurityPosition> findPositionByFigi(Positions positions, String figi) {
        return positions.getSecurities().stream().filter(p -> p.getFigi().equals(figi) && p.getBalance() != 0).findFirst();
    }

    private Optional<StopOrder> findStopOrder(List<StopOrder> orders, String figi, StopOrderType type) {
        return orders.stream().filter(o -> o.getFigi().equals(figi) && o.getOrderType() == type).findFirst();
    }
}