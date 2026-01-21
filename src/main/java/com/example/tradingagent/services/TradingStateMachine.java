package com.example.tradingagent.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Конечный автомат для управления состояниями торговли по каждому тикеру.
 * Состояния:
 * - SCANNING: Ищем точку входа
 * - ENTRY_PENDING: Ордер отправлен, ждем исполнения
 * - ACTIVE: В позиции. Мониторим SL/TP
 * - EXIT_PENDING: Ордер на выход отправлен
 * - COOLDOWN: После убытка или сделки не торгуем этот тикер N минут
 */
@Service
public class TradingStateMachine {

    private static final Logger logger = LoggerFactory.getLogger(TradingStateMachine.class);
    private static final long COOLDOWN_MINUTES = 60; // 1 час после сделки

    private final ConcurrentHashMap<String, TradingState> stateMap = new ConcurrentHashMap<>();

    public enum TradingStateType {
        SCANNING,           // Ищем точку входа
        ENTRY_PENDING,      // Ордер отправлен, ждем исполнения
        ACTIVE,             // В позиции. Мониторим SL/TP
        EXIT_PENDING,       // Ордер на выход отправлен
        COOLDOWN            // После убытка или сделки не торгуем этот тикер N минут
    }

    public static class TradingState {
        private TradingStateType state;
        private Instant timestamp;
        private String additionalInfo;

        public TradingState(TradingStateType state) {
            this.state = state;
            this.timestamp = Instant.now();
        }

        public TradingState(TradingStateType state, String additionalInfo) {
            this.state = state;
            this.timestamp = Instant.now();
            this.additionalInfo = additionalInfo;
        }

        // Getters and Setters
        public TradingStateType getState() {
            return state;
        }

        public void setState(TradingStateType state) {
            this.state = state;
            this.timestamp = Instant.now();
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Instant timestamp) {
            this.timestamp = timestamp;
        }

        public String getAdditionalInfo() {
            return additionalInfo;
        }

        public void setAdditionalInfo(String additionalInfo) {
            this.additionalInfo = additionalInfo;
        }
    }

    /**
     * Получает текущее состояние для тикера.
     */
    public TradingStateType getState(String figi) {
        TradingState state = stateMap.get(figi);
        if (state == null) {
            return TradingStateType.SCANNING;
        }

        // Проверка коулдауна
        if (state.getState() == TradingStateType.COOLDOWN) {
            long minutesSinceCooldown = ChronoUnit.MINUTES.between(state.getTimestamp(), Instant.now());
            if (minutesSinceCooldown >= COOLDOWN_MINUTES) {
                // Коулдаун истек, возвращаемся к сканированию
                state.setState(TradingStateType.SCANNING);
                logger.info("Коулдаун истек для {}, возврат к SCANNING", figi);
            }
        }

        return state.getState();
    }

    /**
     * Переход в состояние ожидания входа.
     */
    public void setEntryPending(String figi, String orderId) {
        TradingState state = new TradingState(TradingStateType.ENTRY_PENDING, orderId);
        stateMap.put(figi, state);
        logger.info("Переход {} в состояние ENTRY_PENDING (orderId: {})", figi, orderId);
    }

    /**
     * Переход в активное состояние (позиция открыта).
     */
    public void setActive(String figi) {
        TradingState state = new TradingState(TradingStateType.ACTIVE);
        stateMap.put(figi, state);
        logger.info("Переход {} в состояние ACTIVE", figi);
    }

    /**
     * Переход в состояние ожидания выхода.
     */
    public void setExitPending(String figi, String orderId) {
        TradingState state = new TradingState(TradingStateType.EXIT_PENDING, orderId);
        stateMap.put(figi, state);
        logger.info("Переход {} в состояние EXIT_PENDING (orderId: {})", figi, orderId);
    }

    /**
     * Переход в коулдаун после сделки (убыток или прибыль).
     */
    public void setCooldown(String figi, boolean wasLoss) {
        TradingState state = new TradingState(TradingStateType.COOLDOWN, 
            wasLoss ? "LOSS" : "PROFIT");
        stateMap.put(figi, state);
        logger.info("Переход {} в состояние COOLDOWN ({}), длительность {} минут", 
            figi, wasLoss ? "убыток" : "прибыль", COOLDOWN_MINUTES);
    }

    /**
     * Сброс состояния в SCANNING.
     */
    public void resetToScanning(String figi) {
        TradingState state = new TradingState(TradingStateType.SCANNING);
        stateMap.put(figi, state);
        logger.info("Сброс {} в состояние SCANNING", figi);
    }

    /**
     * Проверка, можно ли торговать тикер (не в коулдауне и не в процессе исполнения).
     */
    public boolean canTrade(String figi) {
        TradingStateType state = getState(figi);
        return state == TradingStateType.SCANNING;
    }

    /**
     * Проверка, находится ли тикер в активной позиции.
     */
    public boolean isActive(String figi) {
        TradingStateType state = getState(figi);
        return state == TradingStateType.ACTIVE || state == TradingStateType.EXIT_PENDING;
    }
}
