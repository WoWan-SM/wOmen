package com.example.tradingagent.services;

import com.example.tradingagent.entities.*;
import com.example.tradingagent.repositories.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Сервис для аудита и логирования всех действий бота в базу данных.
 * Заменяет System.out.println и файловые логи на структурированное логирование в БД.
 */
@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    private final MarketSnapshotRepository marketSnapshotRepository;
    private final TradeDecisionRepository tradeDecisionRepository;
    private final OrderEntityRepository orderEntityRepository;
    private final PositionEntityRepository positionEntityRepository;

    public AuditService(MarketSnapshotRepository marketSnapshotRepository,
                       TradeDecisionRepository tradeDecisionRepository,
                       OrderEntityRepository orderEntityRepository,
                       PositionEntityRepository positionEntityRepository) {
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.tradeDecisionRepository = tradeDecisionRepository;
        this.orderEntityRepository = orderEntityRepository;
        this.positionEntityRepository = positionEntityRepository;
    }

    /**
     * Сохраняет снимок рынка при анализе тикера.
     */
    @Transactional
    public MarketSnapshot saveMarketSnapshot(String ticker, Map<String, Double> indicators, BigDecimal priceClose) {
        try {
            MarketSnapshot snapshot = new MarketSnapshot(Instant.now(), ticker, priceClose);
            
            if (indicators != null) {
                if (indicators.containsKey("rsi_14")) {
                    snapshot.setRsiValue(BigDecimal.valueOf(indicators.get("rsi_14")));
                }
                if (indicators.containsKey("adx_14")) {
                    snapshot.setAdxValue(BigDecimal.valueOf(indicators.get("adx_14")));
                }
                if (indicators.containsKey("macd_hist")) {
                    snapshot.setMacdValue(BigDecimal.valueOf(indicators.get("macd_hist")));
                }
                if (indicators.containsKey("atr_14")) {
                    snapshot.setAtrValue(BigDecimal.valueOf(indicators.get("atr_14")));
                }
                if (indicators.containsKey("ema_100")) {
                    snapshot.setEmaValue(BigDecimal.valueOf(indicators.get("ema_100")));
                }
                if (indicators.containsKey("current_price")) {
                    snapshot.setCurrentPrice(BigDecimal.valueOf(indicators.get("current_price")));
                }
            }
            
            return marketSnapshotRepository.save(snapshot);
        } catch (Exception e) {
            logger.error("Ошибка сохранения снимка рынка для {}: ", ticker, e);
            return null;
        }
    }

    /**
     * Сохраняет решение стратегии (ВАЖНО: даже если сделка не открылась).
     */
    @Transactional
    public TradeDecision saveTradeDecision(MarketSnapshot snapshot, 
                                          TradeDecision.DecisionType decision,
                                          String reasonCode,
                                          String reasonDetails) {
        try {
            TradeDecision tradeDecision = new TradeDecision(snapshot, decision, reasonCode, reasonDetails);
            return tradeDecisionRepository.save(tradeDecision);
        } catch (Exception e) {
            logger.error("Ошибка сохранения решения о торговле: ", e);
            return null;
        }
    }

    /**
     * Сохраняет или обновляет ордер.
     */
    @Transactional
    public OrderEntity saveOrder(String ticker,
                                OrderEntity.Direction direction,
                                BigDecimal requestedPrice,
                                Long quantityLots,
                                String brokerOrderId) {
        try {
            OrderEntity order = new OrderEntity(ticker, direction, requestedPrice, quantityLots);
            order.setBrokerOrderId(brokerOrderId);
            order.setStatus(OrderEntity.OrderStatus.NEW);
            return orderEntityRepository.save(order);
        } catch (Exception e) {
            logger.error("Ошибка сохранения ордера для {}: ", ticker, e);
            return null;
        }
    }

    /**
     * Обновляет статус ордера после исполнения.
     */
    @Transactional
    public void updateOrderStatus(String brokerOrderId,
                                 OrderEntity.OrderStatus status,
                                 BigDecimal executedPrice,
                                 BigDecimal commission,
                                 String errorMessage) {
        try {
            orderEntityRepository.findByBrokerOrderId(brokerOrderId).ifPresent(order -> {
                order.setStatus(status);
                if (executedPrice != null) {
                    order.setExecutedPrice(executedPrice);
                }
                if (commission != null) {
                    order.setCommission(commission);
                }
                if (errorMessage != null) {
                    order.setErrorMessage(errorMessage);
                }
                orderEntityRepository.save(order);
            });
        } catch (Exception e) {
            logger.error("Ошибка обновления статуса ордера {}: ", brokerOrderId, e);
        }
    }

    /**
     * Создает новую позицию.
     */
    @Transactional
    public PositionEntity createPosition(OrderEntity entryOrder, BigDecimal entryPrice) {
        try {
            PositionEntity position = new PositionEntity(entryOrder, entryPrice);
            return positionEntityRepository.save(position);
        } catch (Exception e) {
            logger.error("Ошибка создания позиции: ", e);
            return null;
        }
    }

    /**
     * Закрывает позицию и рассчитывает PnL.
     */
    @Transactional
    public void closePosition(PositionEntity position, OrderEntity exitOrder, BigDecimal exitPrice) {
        try {
            position.setExitOrder(exitOrder);
            position.setExitPrice(exitPrice);
            
            if (position.getEntryPrice() != null && exitPrice != null) {
                // Расчет абсолютного PnL
                BigDecimal pnlAbsolute = exitPrice.subtract(position.getEntryPrice());
                position.setPnlAbsolute(pnlAbsolute);
                
                // Расчет процентного PnL
                if (position.getEntryPrice().compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal pnlPercent = pnlAbsolute
                        .divide(position.getEntryPrice(), 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                    position.setPnlPercent(pnlPercent);
                }
            }
            
            // Расчет длительности в секундах
            if (position.getTimestamp() != null) {
                long durationSeconds = Instant.now().getEpochSecond() - position.getTimestamp().getEpochSecond();
                position.setDurationSeconds(durationSeconds);
            }
            
            positionEntityRepository.save(position);
        } catch (Exception e) {
            logger.error("Ошибка закрытия позиции {}: ", position.getId(), e);
        }
    }
}
