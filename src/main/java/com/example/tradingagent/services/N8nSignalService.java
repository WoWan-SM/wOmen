package com.example.tradingagent.services;

import com.example.tradingagent.dto.TradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Сервис для генерации торговых сигналов на основе технических индикаторов.
 * Используется как в реальной торговле, так и в бэктесте.
 */
@Service
public class N8nSignalService {

    private static final Logger logger = LoggerFactory.getLogger(N8nSignalService.class);
    
    // Порог силы тренда. Если ADX ниже, торговля запрещена
    private static final double MIN_ADX = 20.0;
    
    // Минимальный скоринг для BUY/SELL
    private static final double MIN_SCORE_FOR_TRADE = 3.0;
    
    // Минимальный confidence_score для исполнения
    private static final double MIN_CONFIDENCE_SCORE = 0.8;
    
    public N8nSignalService() {
        // Конструктор без зависимостей - новости не используются
    }

    /**
     * Генерирует торговый сигнал на основе технических индикаторов.
     * Новости не учитываются (News всегда = 0).
     * 
     * @param instrumentFigi FIGI инструмента
     * @param ticker Тикер инструмента
     * @param indicatorsH1 Индикаторы H1 (current_price, ema_100, macd_hist, adx_14)
     * @param indicatorsM15 Индикаторы M15 (macd_hist, macd_hist_previous)
     * @param orderBook Order book данные (bids_volume, asks_volume) - может быть null
     * @param newsSentiment Игнорируется (новости не учитываются)
     * @param currentPrice Текущая цена (используется если current_price отсутствует в indicatorsH1)
     * @return TradeRequest с action, confidence_score, reason
     */
    public TradeRequest generateTradeSignal(String instrumentFigi, String ticker,
                                           Map<String, Double> indicatorsH1,
                                           Map<String, Double> indicatorsM15,
                                           Map<String, Object> orderBook,
                                           String newsSentiment,
                                           double currentPrice) {
        
        // Новости не учитываются - News всегда = 0
        
        // Извлекаем значения из H1 индикаторов
        Double adxObj = indicatorsH1.get("adx_14");
        double adx = adxObj != null ? adxObj : 0.0;
        
        // --- ФИЛЬТР ADX ---
        if (adx < MIN_ADX) {
            TradeRequest holdSignal = new TradeRequest();
            holdSignal.setAction("HOLD");
            holdSignal.setInstrumentFigi(instrumentFigi);
            holdSignal.setTicker(ticker);
            holdSignal.setConfidenceScore(0.0);
            holdSignal.setReason(String.format("Low ADX (%.1f < %.1f). Flat market.", adx, MIN_ADX));
            return holdSignal;
        }
        
        // Используем current_price из индикаторов или переданный параметр
        double h1CurrentPrice = indicatorsH1.getOrDefault("current_price", currentPrice);
        double h1Ema100 = indicatorsH1.getOrDefault("ema_100", 0.0);
        double h1MacdHist = indicatorsH1.getOrDefault("macd_hist", 0.0);
        
        // Извлекаем значения из M15 индикаторов
        double m15MacdHist = indicatorsM15.getOrDefault("macd_hist", 0.0);
        double m15MacdHistPrevious = indicatorsM15.getOrDefault("macd_hist_previous", 0.0);
        
        double buyScore = 0.0;
        double sellScore = 0.0;
        StringBuilder buyReason = new StringBuilder(String.format("ADX=%.1f, ", adx));
        StringBuilder sellReason = new StringBuilder(String.format("ADX=%.1f, ", adx));
        
        // --- Оценка факторов для ПОКУПКИ (BUY) ---
        // H1 Trend: current_price > ema_100 && macd_hist > 0 → +2
        if (h1CurrentPrice > h1Ema100 && h1MacdHist > 0) {
            buyScore += 2.0;
            buyReason.append("H1 Trend=2, ");
        } else {
            buyReason.append("H1 Trend=0, ");
        }
        
        // M15 Entry: macd_hist > 0 && macd_hist > macd_hist_previous → +1
        if (m15MacdHist > 0 && m15MacdHist > m15MacdHistPrevious) {
            buyScore += 1.0;
            buyReason.append("M15 Entry=1, ");
        } else {
            buyReason.append("M15 Entry=0, ");
        }
        
        // Order Book: bids_volume > asks_volume → +1
        if (orderBook != null) {
            Long bidsVolume = getLongValue(orderBook.get("bids_volume"));
            Long asksVolume = getLongValue(orderBook.get("asks_volume"));
            if (bidsVolume != null && asksVolume != null && bidsVolume > asksVolume) {
                buyScore += 1.0;
                buyReason.append("Order Book=1, ");
            } else {
                buyReason.append("Order Book=0, ");
            }
        } else {
            buyReason.append("Order Book=0, ");
        }
        
        // News: не учитывается (всегда 0)
        buyReason.append("News=0. ");
        
        // --- Оценка факторов для ПРОДАЖИ (SELL) ---
        // H1 Trend: current_price < ema_100 && macd_hist < 0 → +2
        if (h1CurrentPrice < h1Ema100 && h1MacdHist < 0) {
            sellScore += 2.0;
            sellReason.append("H1 Trend=2, ");
        } else {
            sellReason.append("H1 Trend=0, ");
        }
        
        // M15 Entry: macd_hist < 0 && macd_hist < macd_hist_previous → +1
        if (m15MacdHist < 0 && m15MacdHist < m15MacdHistPrevious) {
            sellScore += 1.0;
            sellReason.append("M15 Entry=1, ");
        } else {
            sellReason.append("M15 Entry=0, ");
        }
        
        // Order Book: asks_volume > bids_volume → +1
        if (orderBook != null) {
            Long bidsVolume = getLongValue(orderBook.get("bids_volume"));
            Long asksVolume = getLongValue(orderBook.get("asks_volume"));
            if (bidsVolume != null && asksVolume != null && asksVolume > bidsVolume) {
                sellScore += 1.0;
                sellReason.append("Order Book=1, ");
            } else {
                sellReason.append("Order Book=0, ");
            }
        } else {
            sellReason.append("Order Book=0, ");
        }
        
        // News: не учитывается (всегда 0)
        sellReason.append("News=0. ");
        
        // --- Финальное решение ---
        TradeRequest signal = new TradeRequest();
        signal.setInstrumentFigi(instrumentFigi);
        signal.setTicker(ticker);
        
        // Логирование для отладки
        logger.debug("Скоринг для {}: BUY={}, SELL={}, News={}", ticker, buyScore, sellScore, newsSentiment);
        
        if (buyScore >= MIN_SCORE_FOR_TRADE) {
            signal.setAction("BUY");
            signal.setConfidenceScore(0.9);
            signal.setReason(buyReason.toString() + String.format("Total: %.1f -> BUY", buyScore));
            logger.info("BUY сигнал для {}: score={}, reason={}", ticker, buyScore, signal.getReason());
            return signal;
        } else if (sellScore >= MIN_SCORE_FOR_TRADE) {
            signal.setAction("SELL");
            signal.setConfidenceScore(0.9);
            signal.setReason(sellReason.toString() + String.format("Total: %.1f -> SELL", sellScore));
            logger.info("SELL сигнал для {}: score={}, reason={}", ticker, sellScore, signal.getReason());
            return signal;
        } else {
            signal.setAction("HOLD");
            signal.setConfidenceScore(0.0);
            signal.setReason(String.format("Total: %.1f -> HOLD", Math.max(buyScore, sellScore)));
            logger.debug("HOLD для {}: maxScore={}, buyScore={}, sellScore={}", ticker, Math.max(buyScore, sellScore), buyScore, sellScore);
            return signal;
        }
    }
    
    /**
     * Проверяет, можно ли исполнять сигнал (confidence_score >= 0.8)
     */
    public boolean canExecuteSignal(TradeRequest signal) {
        if (signal == null) {
            return false;
        }
        
        // Проверка confidence_score (confidence_score >= 0.8)
        Double confidenceScore = signal.getConfidenceScore();
        if (confidenceScore == null || confidenceScore < MIN_CONFIDENCE_SCORE) {
            return false;
        }
        
        // Только BUY и SELL могут быть исполнены
        String action = signal.getAction();
        return "BUY".equalsIgnoreCase(action) || "SELL".equalsIgnoreCase(action);
    }
    
    /**
     * Вспомогательный метод для безопасного преобразования в Long
     */
    private Long getLongValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
