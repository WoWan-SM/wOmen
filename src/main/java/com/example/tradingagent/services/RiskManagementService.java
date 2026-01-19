package com.example.tradingagent.services;

import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.Instrument;

import java.math.BigDecimal;

/**
 * Сервис для расчета объема позиции.
 * Упрощенная версия: возвращает фиксированный 1 лот, как было изначально.
 */
@Service
public class RiskManagementService {

    /**
     * Возвращает 1 лот, если на балансе достаточно средств.
     * Параметр stopLossPrice оставлен для совместимости с интерфейсом вызова, но не используется.
     *
     * @param balance       Текущий свободный баланс (рубли).
     * @param entryPrice    Цена входа.
     * @param stopLossPrice Цена стоп-лосса (игнорируется).
     * @param instrument    Инструмент (для получения размера лота).
     * @return 1, если хватает средств, иначе 0.
     */
    public long calculateSafeLotSize(BigDecimal balance, BigDecimal entryPrice, BigDecimal stopLossPrice, Instrument instrument) {
        // Базовая проверка баланса
        if (balance == null || balance.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }

        int lotSize = instrument.getLot();
        BigDecimal lotPrice = entryPrice.multiply(BigDecimal.valueOf(lotSize));

        // Простая логика: если денег хватает на 1 лот — берем 1 лот.
        if (balance.compareTo(lotPrice) >= 0) {
            return 1;
        } else {
            // Если денег не хватает даже на 1 лот
            return 0;
        }
    }
}