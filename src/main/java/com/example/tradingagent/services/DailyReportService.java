package com.example.tradingagent.services;

import com.example.tradingagent.entities.PositionEntity;
import com.example.tradingagent.repositories.PositionEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Сервис для генерации ежедневных отчетов и отправки в Telegram.
 */
@Service
public class DailyReportService {

    private static final Logger logger = LoggerFactory.getLogger(DailyReportService.class);
    private final PositionEntityRepository positionRepository;
    private final TelegramNotificationService telegramService;

    public DailyReportService(PositionEntityRepository positionRepository,
                             TelegramNotificationService telegramService) {
        this.positionRepository = positionRepository;
        this.telegramService = telegramService;
    }

    /**
     * Генерирует и отправляет ежедневный отчет в 23:00 каждый день.
     */
    @Scheduled(cron = "0 0 23 * * *") // Каждый день в 23:00
    public void generateDailyReport() {
        try {
            LocalDate today = LocalDate.now();
            Instant startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

            // Получаем все закрытые позиции за сегодня
            List<PositionEntity> allPositions = positionRepository.findByTimestampBetween(startOfDay, endOfDay);
            List<PositionEntity> closedPositions = allPositions.stream()
                .filter(p -> p.getExitOrder() != null)
                .toList();

            int totalTrades = closedPositions.size();
            int profitableTrades = 0;
            int losingTrades = 0;
            BigDecimal totalPnL = BigDecimal.ZERO;

            for (PositionEntity position : closedPositions) {
                if (position.getPnlAbsolute() != null) {
                    totalPnL = totalPnL.add(position.getPnlAbsolute());
                    if (position.getPnlAbsolute().compareTo(BigDecimal.ZERO) > 0) {
                        profitableTrades++;
                    } else if (position.getPnlAbsolute().compareTo(BigDecimal.ZERO) < 0) {
                        losingTrades++;
                    }
                }
            }

            // Отправляем отчет в Telegram
            telegramService.notifyDailyReport(totalPnL, totalTrades, profitableTrades, losingTrades);
            
            logger.info("Ежедневный отчет отправлен: PnL={}, Сделок={}, Прибыльных={}, Убыточных={}", 
                totalPnL, totalTrades, profitableTrades, losingTrades);
        } catch (Exception e) {
            logger.error("Ошибка генерации ежедневного отчета: ", e);
        }
    }
}
