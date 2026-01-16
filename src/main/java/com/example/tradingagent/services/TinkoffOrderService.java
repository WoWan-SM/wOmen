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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Service
public class TinkoffOrderService {

    private final InvestApi api;
    private final String accountId;

    private static final BigDecimal ATR_MULTIPLIER_STOP_LOSS = new BigDecimal("2.0");
    private static final BigDecimal ATR_MULTIPLIER_TAKE_PROFIT = new BigDecimal("4.0");

    public TinkoffOrderService(TinkoffAccountService accountService) {
        this.api = TinkoffApi.getApi();
        this.accountId = accountService.getSandboxAccountId();
    }

    public void executeTrade(TradeRequest tradeRequest, Map<String, Double> indicators) {
        String requestedFigi = tradeRequest.getInstrumentFigi();
        Instrument instrument = api.getInstrumentsService().getInstrumentByFigiSync(requestedFigi);
        String ticker = instrument.getTicker();

        if ("SNGS".equalsIgnoreCase(ticker)) {
            System.out.println("Инструмент " + ticker + " находится в списке исключений. Торговая операция пропущена.");
            return;
        }

        Positions positions = api.getOperationsService().getPositionsSync(accountId);

        Optional<SecurityPosition> existingPositionOpt = findPositionByFigi(positions, requestedFigi);

        if (existingPositionOpt.isPresent()) {
            handleExistingPosition(existingPositionOpt.get(), tradeRequest, indicators, instrument);
        } else {
            if ("BUY".equalsIgnoreCase(tradeRequest.getAction())) {
                openNewPosition(tradeRequest, indicators, instrument, PositionType.LONG);
            } else if ("SELL".equalsIgnoreCase(tradeRequest.getAction())) {
                openNewPosition(tradeRequest, indicators, instrument, PositionType.SHORT);
            }
        }
    }

    private enum PositionType { LONG, SHORT }

    private void handleExistingPosition(SecurityPosition position, TradeRequest tradeRequest, Map<String, Double> indicators, Instrument instrument) {
        String ticker = instrument.getTicker();
        System.out.println("Новый сигнал для уже открытой позиции по " + ticker + ". Пробуем обновить защитные ордера.");

        var activeStopOrders = api.getStopOrdersService().getStopOrdersSync(accountId);

        Optional<StopOrder> existingStopOrderOpt = findStopOrder(activeStopOrders, position.getFigi(), StopOrderType.STOP_ORDER_TYPE_STOP_LOSS);

        if (existingStopOrderOpt.isEmpty()) {
            System.err.println("ВНИМАНИЕ: Не найден активный стоп-ордер для открытой позиции " + ticker + ". Создаем новый защитный ордер.");
            createProtectiveOrdersForPosition(position, indicators, instrument);
            return;
        }

        StopOrder existingStopOrder = existingStopOrderOpt.get();
        BigDecimal oldStopLossPrice = TinkoffApiUtils.moneyValueToBigDecimal(existingStopOrder.getStopPrice());

        BigDecimal currentPrice = BigDecimal.valueOf(indicators.getOrDefault("current_price", 0.0));
        double atrValue = indicators.getOrDefault("atr_14", 0.0);
        if (currentPrice.compareTo(BigDecimal.ZERO) == 0 || atrValue == 0.0) {
            System.err.println("Недостаточно данных для обновления стоп-лосса для " + ticker);
            return;
        }

        BigDecimal minPriceIncrement = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());

        if (existingStopOrder.getDirection() == StopOrderDirection.STOP_ORDER_DIRECTION_SELL && "BUY".equalsIgnoreCase(tradeRequest.getAction())) {
            BigDecimal newStopLossPrice = currentPrice.subtract(BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS));
            BigDecimal roundedNewStopLossPrice = TinkoffApiUtils.roundToStep(newStopLossPrice, minPriceIncrement);

            if (roundedNewStopLossPrice.compareTo(oldStopLossPrice) > 0) {
                System.out.printf("Обновляем стоп-лосс для LONG по %s: Новый SL (%s) > Старый SL (%s)%n", ticker, roundedNewStopLossPrice.toPlainString(), oldStopLossPrice.toPlainString());
                updateProtectiveOrders(existingStopOrder, roundedNewStopLossPrice, instrument);
            }
        }
        else if (existingStopOrder.getDirection() == StopOrderDirection.STOP_ORDER_DIRECTION_BUY && "SELL".equalsIgnoreCase(tradeRequest.getAction())) {
            BigDecimal newStopLossPrice = currentPrice.add(BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS));
            BigDecimal roundedNewStopLossPrice = TinkoffApiUtils.roundToStep(newStopLossPrice, minPriceIncrement);

            if (roundedNewStopLossPrice.compareTo(oldStopLossPrice) < 0) {
                System.out.printf("Обновляем стоп-лосс для SHORT по %s: Новый SL (%s) < Старый SL (%s)%n", ticker, roundedNewStopLossPrice.toPlainString(), oldStopLossPrice.toPlainString());
                updateProtectiveOrders(existingStopOrder, roundedNewStopLossPrice, instrument);
            }
        }
    }

    private void createProtectiveOrdersForPosition(SecurityPosition position, Map<String, Double> indicators, Instrument instrument) {
        BigDecimal currentPrice = BigDecimal.valueOf(indicators.getOrDefault("current_price", 0.0));
        double atrValue = indicators.getOrDefault("atr_14", 0.0);
        BigDecimal stopLossOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS);
        BigDecimal takeProfitOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_TAKE_PROFIT);
        BigDecimal minPriceIncrement = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());

        long quantityInLots = position.getBalance() / instrument.getLot();
        boolean isLong = quantityInLots > 0;

        BigDecimal stopLossPrice, takeProfitPrice;
        StopOrderDirection direction;

        if (isLong) {
            stopLossPrice = currentPrice.subtract(stopLossOffset);
            takeProfitPrice = currentPrice.add(takeProfitOffset);
            direction = StopOrderDirection.STOP_ORDER_DIRECTION_SELL;
        } else {
            stopLossPrice = currentPrice.add(stopLossOffset);
            takeProfitPrice = currentPrice.subtract(takeProfitOffset);
            direction = StopOrderDirection.STOP_ORDER_DIRECTION_BUY;
        }

        BigDecimal roundedStopLossPrice = TinkoffApiUtils.roundToStep(stopLossPrice, minPriceIncrement);
        BigDecimal roundedTakeProfitPrice = TinkoffApiUtils.roundToStep(takeProfitPrice, minPriceIncrement);

        try {
            postStopLossOrder(position.getFigi(), Math.abs(quantityInLots), roundedStopLossPrice, direction, instrument.getTicker(), minPriceIncrement);
            postTakeProfitOrder(position.getFigi(), Math.abs(quantityInLots), roundedTakeProfitPrice, direction, instrument.getTicker());
        } catch (Exception e) {
            System.err.println("Ошибка при создании защитных ордеров для существующей позиции: " + e.getMessage());
        }
    }

    private void updateProtectiveOrders(StopOrder existingStopOrder, BigDecimal newStopLossPrice, Instrument instrument) {
        try {
            api.getStopOrdersService().cancelStopOrderSync(accountId, existingStopOrder.getStopOrderId());
            System.out.println("Старый стоп-ордер отменен: " + existingStopOrder.getStopOrderId());
            Thread.sleep(500);

            BigDecimal minPriceIncrement = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());

            postStopLossOrder(
                    existingStopOrder.getFigi(),
                    existingStopOrder.getLotsRequested(),
                    newStopLossPrice,
                    existingStopOrder.getDirection(),
                    instrument.getTicker(),
                    minPriceIncrement
            );
        } catch (Exception e) {
            System.err.println("Ошибка при обновлении защитных ордеров: " + e.getMessage());
        }
    }

    private void openNewPosition(TradeRequest tradeRequest, Map<String, Double> indicators, Instrument instrument, PositionType positionType) {
        String ticker = instrument.getTicker();
        System.out.println("Получен приказ на открытие новой " + positionType + " позиции: " + ticker);

        try {
            var marginAttributes = api.getUserService().getMarginAttributesSync(accountId);
            BigDecimal liquidPortfolio = TinkoffApiUtils.moneyValueToBigDecimal(marginAttributes.getLiquidPortfolio());
            BigDecimal startingMargin = TinkoffApiUtils.moneyValueToBigDecimal(marginAttributes.getStartingMargin());
            BigDecimal availableMargin = liquidPortfolio.subtract(startingMargin);

            BigDecimal currentPrice = BigDecimal.valueOf(indicators.getOrDefault("current_price", 0.0));
            BigDecimal tradeAmount = currentPrice.multiply(BigDecimal.valueOf(instrument.getLot()));

            if(availableMargin.compareTo(tradeAmount) < 0) {
                System.out.printf("Недостаточно средств для открытия позиции по %s. Доступно: %s, Требуется: %s%n",
                        ticker, availableMargin.toPlainString(), tradeAmount.toPlainString());
                return;
            }

            String orderId = UUID.randomUUID().toString();
            OrderDirection orderDirection = (positionType == PositionType.LONG) ? OrderDirection.ORDER_DIRECTION_BUY : OrderDirection.ORDER_DIRECTION_SELL;

            api.getOrdersService().postOrderSync(
                    instrument.getFigi(), 1, Quotation.newBuilder().build(), orderDirection,
                    accountId, OrderType.ORDER_TYPE_MARKET, orderId
            );

            System.out.println("Рыночный ордер на " + positionType + " для " + ticker + " выставлен.");

            boolean positionOpened = waitForPosition(instrument.getFigi(), 10, 1500);

            if (positionOpened) {
                System.out.println("Позиция по " + ticker + " подтверждена. Выставляем защитные ордера.");

                double atrValue = indicators.getOrDefault("atr_14", 0.0);
                BigDecimal minPriceIncrement = TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());
                BigDecimal stopLossOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_STOP_LOSS);
                BigDecimal takeProfitOffset = BigDecimal.valueOf(atrValue).multiply(ATR_MULTIPLIER_TAKE_PROFIT);

                BigDecimal stopLossPrice, takeProfitPrice;
                StopOrderDirection stopOrderDirection;

                if (positionType == PositionType.LONG) {
                    stopLossPrice = currentPrice.subtract(stopLossOffset);
                    takeProfitPrice = currentPrice.add(takeProfitOffset);
                    stopOrderDirection = StopOrderDirection.STOP_ORDER_DIRECTION_SELL;
                } else {
                    stopLossPrice = currentPrice.add(stopLossOffset);
                    takeProfitPrice = currentPrice.subtract(takeProfitOffset);
                    stopOrderDirection = StopOrderDirection.STOP_ORDER_DIRECTION_BUY;
                }

                BigDecimal roundedStopLossPrice = TinkoffApiUtils.roundToStep(stopLossPrice, minPriceIncrement);
                BigDecimal roundedTakeProfitPrice = TinkoffApiUtils.roundToStep(takeProfitPrice, minPriceIncrement);

                postStopLossOrder(instrument.getFigi(), 1, roundedStopLossPrice, stopOrderDirection, ticker, minPriceIncrement);
                postTakeProfitOrder(instrument.getFigi(), 1, roundedTakeProfitPrice, stopOrderDirection, ticker);

            } else {
                System.err.println("КРИТИЧЕСКАЯ ОШИБКА: Позиция по " + ticker + " не появилась на счете после отправки ордера. Защитные ордера не выставлены!");
            }

        } catch (Exception e) {
            System.err.println("Критическая ошибка при открытии " + positionType + " позиции для " + ticker + ": " + e.getMessage());
        }
    }

    private void postStopLossOrder(String figi, long quantity, BigDecimal stopPrice, StopOrderDirection direction, String ticker, BigDecimal minPriceIncrement) {
        try {
            BigDecimal slippage = minPriceIncrement.multiply(BigDecimal.valueOf(10));
            BigDecimal executionPrice = (direction == StopOrderDirection.STOP_ORDER_DIRECTION_SELL) ?
                    stopPrice.subtract(slippage) :
                    stopPrice.add(slippage);

            String stopOrderId = api.getStopOrdersService().postStopOrderGoodTillCancelSync(
                    figi,
                    quantity,
                    TinkoffApiUtils.bigDecimalToQuotation(executionPrice),
                    TinkoffApiUtils.bigDecimalToQuotation(stopPrice),
                    direction,
                    accountId,
                    StopOrderType.STOP_ORDER_TYPE_STOP_LIMIT
            );
            System.out.printf("Успешно выставлен СТОП-ЛОСС (Stop-Limit) для %s: активация %s, исполнение до %s (ID: %s)%n",
                    ticker, stopPrice.toPlainString(), executionPrice.toPlainString(), stopOrderId);
        } catch (Exception e) {
            System.err.println("Ошибка при выставлении СТОП-ЛОСС для " + ticker + ": " + e.getMessage());
        }
    }

    private void postTakeProfitOrder(String figi, long quantity, BigDecimal takeProfitPrice, StopOrderDirection direction, String ticker) {
        try {
            String takeProfitId = api.getStopOrdersService().postStopOrderGoodTillCancelSync(
                    figi,
                    quantity,
                    TinkoffApiUtils.bigDecimalToQuotation(takeProfitPrice),
                    TinkoffApiUtils.bigDecimalToQuotation(takeProfitPrice),
                    direction,
                    accountId,
                    StopOrderType.STOP_ORDER_TYPE_TAKE_PROFIT
            );
            System.out.printf("Успешно выставлен ТЕЙК-ПРОФИТ для %s по цене %s (ID: %s)%n", ticker, takeProfitPrice.toPlainString(), takeProfitId);
        } catch (Exception e) {
            System.err.println("Ошибка при выставлении ТЕЙК-ПРОФИТ для " + ticker + ": " + e.getMessage());
        }
    }

    private boolean waitForPosition(String figi, int maxRetries, long delayMillis) throws InterruptedException {
        System.out.println("Ожидаем появления позиции для " + figi + " на счете...");
        for (int i = 0; i < maxRetries; i++) {
            System.out.printf("Попытка %d из %d...%n", i + 1, maxRetries);
            var positions = api.getOperationsService().getPositionsSync(accountId);
            if (findPositionByFigi(positions, figi).isPresent()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(delayMillis);
        }
        return false;
    }

    private Optional<SecurityPosition> findPositionByFigi(Positions positions, String figi) {
        for (SecurityPosition p : positions.getSecurities()) {
            if (p.getFigi().equals(figi) && p.getBalance() != 0) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private Optional<StopOrder> findStopOrder(List<StopOrder> orders, String figi, StopOrderType type) {
        for (StopOrder order : orders) {
            if (order.getFigi().equals(figi) && order.getOrderType() == type) {
                return Optional.of(order);
            }
        }
        return Optional.empty();
    }
}

