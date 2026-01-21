package com.example.tradingagent.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Сервис для отправки уведомлений в Telegram.
 * Согласно roadmap: "Купил SBER по 250", "ERROR: Не хватает денег", "Daily Report: PnL -50 rub"
 */
@Service
public class TelegramNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramNotificationService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    
    @Value("${telegram.bot.token:}")
    private String botToken;
    
    @Value("${telegram.chat.id:}")
    private String chatId;
    
    private boolean isEnabled() {
        return botToken != null && !botToken.isEmpty() && chatId != null && !chatId.isEmpty();
    }

    /**
     * Отправляет сообщение в Telegram.
     */
    private void sendMessage(String message) {
        if (!isEnabled()) {
            logger.debug("Telegram уведомления отключены");
            return;
        }

        try {
            String url = String.format(
                    "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s",
                    botToken, chatId, message
            );

            restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            logger.error("Ошибка отправки Telegram сообщения", e);
        }
    }


    /**
     * Уведомление о покупке/продаже.
     */
    public void notifyTrade(String action, String ticker, BigDecimal price, Long lots) {
        String message = String.format("✅ %s %s по %.2f (лотов: %d)", 
            action.equalsIgnoreCase("BUY") ? "Купил" : "Продал", 
            ticker, price, lots);
        sendMessage(message);
    }

    /**
     * Уведомление об ошибке.
     */
    public void notifyError(String errorMessage) {
        String message = "❌ ERROR: " + errorMessage;
        sendMessage(message);
    }

    /**
     * Уведомление об отказе в торговле.
     */
    public void notifyRejection(String ticker, String reason) {
        String message = String.format("⛔ ОТКАЗ: %s - %s", ticker, reason);
        sendMessage(message);
    }

    /**
     * Уведомление об открытии позиции.
     */
    public void notifyPositionOpened(String ticker, BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
        String message = String.format(
            "📈 ПОЗИЦИЯ ОТКРЫТА: %s\n" +
            "Вход: %.2f\n" +
            "SL: %.2f\n" +
            "TP: %.2f",
            ticker, entryPrice, stopLoss, takeProfit);
        sendMessage(message);
    }

    /**
     * Уведомление о закрытии позиции.
     */
    public void notifyPositionClosed(String ticker, BigDecimal entryPrice, BigDecimal exitPrice, 
                                     BigDecimal pnlAbsolute, BigDecimal pnlPercent, boolean wasLoss) {
        String emoji = wasLoss ? "📉" : "💰";
        String message = String.format(
            "%s ПОЗИЦИЯ ЗАКРЫТА: %s\n" +
            "Вход: %.2f → Выход: %.2f\n" +
            "PnL: %.2f руб (%.2f%%)",
            emoji, ticker, entryPrice, exitPrice, pnlAbsolute, pnlPercent);
        sendMessage(message);
    }

    /**
     * Ежедневный отчет.
     */
    public void notifyDailyReport(BigDecimal totalPnL, int totalTrades, int profitableTrades, int losingTrades) {
        String emoji = totalPnL.compareTo(BigDecimal.ZERO) >= 0 ? "📊" : "📉";
        String message = String.format(
            "%s ДНЕВНОЙ ОТЧЕТ:\n" +
            "PnL: %.2f руб\n" +
            "Сделок: %d (Прибыльных: %d, Убыточных: %d)",
            emoji, totalPnL, totalTrades, profitableTrades, losingTrades);
        sendMessage(message);
    }

    /**
     * Уведомление о важных событиях (например, превышение лимита убытка).
     */
    public void notifyImportantEvent(String event) {
        String message = "⚠️ ВАЖНО: " + event;
        sendMessage(message);
    }
}
