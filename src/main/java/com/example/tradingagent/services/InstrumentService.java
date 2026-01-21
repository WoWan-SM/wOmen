package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.Instrument;
import ru.tinkoff.piapi.core.InvestApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис для управления информацией об инструментах.
 * Кэширует данные об инструменте (lot, minPriceIncrement) для оптимизации.
 * Реализует логику расчета количества лотов.
 */
@Service
public class InstrumentService {

    private static final Logger logger = LoggerFactory.getLogger(InstrumentService.class);
    private final InvestApi api;
    private final ConcurrentHashMap<String, InstrumentInfo> instrumentCache = new ConcurrentHashMap<>();

    public InstrumentService() {
        this.api = TinkoffApi.getApi();
    }

    /**
     * Получает информацию об инструменте (кэширует).
     *
     * @param figi FIGI инструмента.
     * @return Информация об инструменте или null, если не найдено.
     */
    public InstrumentInfo getInstrumentInfo(String figi) {
        return instrumentCache.computeIfAbsent(figi, f -> {
            try {
                Instrument instrument = api.getInstrumentsService().getInstrumentByFigiSync(f);
                int lot = instrument.getLot();
                BigDecimal minPriceIncrement = com.example.tradingagent.TinkoffApiUtils.quotationToBigDecimal(instrument.getMinPriceIncrement());
                
                logger.debug("Кэширование инструмента {}: lot={}, minPriceIncrement={}", 
                    instrument.getTicker(), lot, minPriceIncrement);
                
                return new InstrumentInfo(lot, minPriceIncrement, instrument.getTicker());
            } catch (Exception e) {
                logger.error("Ошибка получения информации об инструменте {}: {}", figi, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Рассчитывает количество лотов для покупки на заданную сумму.
     * Всегда округляет вниз до целого лота.
     *
     * @param figi         FIGI инструмента.
     * @param targetAmount Целевая сумма в рублях.
     * @param price        Текущая цена инструмента.
     * @return Количество лотов (целое число, округленное вниз).
     */
    public long calculateLotSize(String figi, BigDecimal targetAmount, BigDecimal price) {
        InstrumentInfo info = getInstrumentInfo(figi);
        if (info == null || price.compareTo(BigDecimal.ZERO) <= 0 || targetAmount.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("Невозможно рассчитать лотность для {}: info={}, price={}, amount={}", 
                figi, info != null, price, targetAmount);
            return 0;
        }

        // Количество штук = targetAmount / price
        BigDecimal quantityInUnits = targetAmount.divide(price, 10, RoundingMode.HALF_UP);
        
        // Количество лотов = quantityInUnits / lotSize, округляем вниз
        BigDecimal lots = quantityInUnits.divide(BigDecimal.valueOf(info.getLot()), 0, RoundingMode.FLOOR);
        
        long result = lots.longValue();
        logger.debug("Расчет лотности для {}: amount={}, price={}, lotSize={}, result={} лотов", 
            info.getTicker(), targetAmount, price, info.getLot(), result);
        
        return result;
    }

    /**
     * Класс для хранения информации об инструменте.
     */
    public static class InstrumentInfo {
        private final int lot;
        private final BigDecimal minPriceIncrement;
        private final String ticker;

        public InstrumentInfo(int lot, BigDecimal minPriceIncrement, String ticker) {
            this.lot = lot;
            this.minPriceIncrement = minPriceIncrement;
            this.ticker = ticker;
        }

        public int getLot() {
            return lot;
        }

        public BigDecimal getMinPriceIncrement() {
            return minPriceIncrement;
        }

        public String getTicker() {
            return ticker;
        }
    }
}
