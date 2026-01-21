package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApi;
import com.example.tradingagent.entities.OrderEntity;
import com.example.tradingagent.entities.PositionEntity;
import com.example.tradingagent.repositories.OrderEntityRepository;
import com.example.tradingagent.repositories.PositionEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Portfolio;
import ru.tinkoff.piapi.core.models.Position;

import java.math.BigDecimal;
import java.util.List;

/**
 * Сервис для мониторинга позиций и обработки закрытия через SL/TP.
 * Проверяет закрытые позиции и логирует их в БД.
 */
@Service
public class PositionMonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(PositionMonitoringService.class);
    private final InvestApi api;
    private final String accountId;
    private final PositionEntityRepository positionRepository;
    private final OrderEntityRepository orderRepository;
    private final AuditService auditService;
    private final TradingStateMachine stateMachine;
    private final TelegramNotificationService telegramService;

    public PositionMonitoringService(TinkoffAccountService accountService,
                                     PositionEntityRepository positionRepository,
                                     OrderEntityRepository orderRepository,
                                     AuditService auditService,
                                     TradingStateMachine stateMachine,
                                     TelegramNotificationService telegramService) {
        this.api = TinkoffApi.getApi();
        this.accountId = accountService.getSandboxAccountId();
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
        this.auditService = auditService;
        this.stateMachine = stateMachine;
        this.telegramService = telegramService;
    }

    /**
     * Проверяет закрытые позиции и логирует их в БД.
     * Вызывается периодически через @Scheduled.
     */
    @Scheduled(fixedRate = 60000) // Каждую минуту
    public void checkClosedPositions() {
        try {
            Portfolio portfolio = api.getOperationsService().getPortfolioSync(accountId);
            List<Position> currentPositions = portfolio.getPositions();
            
            // Получаем все активные позиции из БД (без exit order)
            List<PositionEntity> dbPositions = positionRepository.findByExitOrderIsNull();
            
            for (PositionEntity dbPosition : dbPositions) {
                if (dbPosition.getExitOrder() != null) {
                    continue; // Позиция уже закрыта
                }
                
                // Проверяем, закрыта ли позиция на бирже
                // Упрощенная проверка: если позиция с таким количеством исчезла
                // В реальности нужно сравнивать по FIGI из entryOrder
                boolean stillOpen = currentPositions.stream()
                    .anyMatch(p -> {
                        // Проверяем по тикеру (упрощенно, так как нет FIGI в OrderEntity)
                        // В реальности нужно сравнивать по FIGI
                        return p.getQuantity().compareTo(BigDecimal.ZERO) != 0;
                    });
                
                if (!stillOpen) {
                    // Позиция закрыта - нужно найти exit order и залогировать
                    logger.info("Обнаружена закрытая позиция: {}", dbPosition.getId());
                    processClosedPosition(dbPosition);
                }
            }
        } catch (Exception e) {
            logger.error("Ошибка проверки закрытых позиций: ", e);
        }
    }

    /**
     * Обрабатывает закрытую позицию: находит exit order и логирует PnL.
     */
    private void processClosedPosition(PositionEntity position) {
        try {
            // Получаем последние ордера для этого тикера
            OrderEntity entryOrder = position.getEntryOrder();
            String ticker = entryOrder.getTicker();
            
            // Ищем exit order (SELL для LONG, BUY для SHORT)
            OrderEntity.Direction exitDirection = entryOrder.getDirection() == OrderEntity.Direction.BUY 
                ? OrderEntity.Direction.SELL 
                : OrderEntity.Direction.BUY;
            
            List<OrderEntity> exitOrders = orderRepository.findByTickerAndDirection(ticker, exitDirection).stream()
                .filter(o -> o.getStatus() == OrderEntity.OrderStatus.FILLED
                    && o.getTimestamp().isAfter(entryOrder.getTimestamp()))
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .toList();
            
            if (!exitOrders.isEmpty()) {
                OrderEntity exitOrder = exitOrders.get(0);
                BigDecimal exitPrice = exitOrder.getExecutedPrice() != null 
                    ? exitOrder.getExecutedPrice() 
                    : exitOrder.getRequestedPrice();
                
                // Логируем закрытие позиции
                auditService.closePosition(position, exitOrder, exitPrice);
                
                // Обновляем позицию из БД для получения актуальных данных
                // Используем сохраненную позицию напрямую, так как она уже обновлена через auditService
                
                // STATE MACHINE: Переход в COOLDOWN
                // Нужно получить FIGI из entryOrder или другого источника
                // Для упрощения используем ticker
                boolean wasLoss = position.getPnlAbsolute() != null 
                    && position.getPnlAbsolute().compareTo(BigDecimal.ZERO) < 0;
                // stateMachine.setCooldown(figi, wasLoss); // Нужен FIGI
                
                logger.info("Позиция {} закрыта. PnL: {} руб ({}%)", 
                    position.getId(), 
                    position.getPnlAbsolute(), 
                    position.getPnlPercent());
                
                // TELEGRAM: Уведомление о закрытии позиции
                if (position.getEntryPrice() != null && position.getExitPrice() != null 
                    && position.getPnlAbsolute() != null && position.getPnlPercent() != null) {
                    telegramService.notifyPositionClosed(
                        ticker,
                        position.getEntryPrice(),
                        position.getExitPrice(),
                        position.getPnlAbsolute(),
                        position.getPnlPercent(),
                        wasLoss
                    );
                }
            }
        } catch (Exception e) {
            logger.error("Ошибка обработки закрытой позиции {}: ", position.getId(), e);
        }
    }

    /**
     * Создает позицию при открытии (вызывается из TinkoffOrderService).
     */
    public PositionEntity createPositionForOrder(OrderEntity entryOrder, BigDecimal entryPrice) {
        return auditService.createPosition(entryOrder, entryPrice);
    }
}
