package com.example.tradingagent;

import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.Quotation;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Вспомогательный класс для работы с типами данных Tinkoff API.
 */
public class TinkoffApiUtils {

    /**
     * Конвертирует объект Quotation из API в BigDecimal.
     * Quotation состоит из целой части (units) и дробной (nano).
     * @param quotation объект Quotation
     * @return BigDecimal представление цены
     */
    public static BigDecimal quotationToBigDecimal(Quotation quotation) {
        if (quotation == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal units = BigDecimal.valueOf(quotation.getUnits());
        BigDecimal nano = BigDecimal.valueOf(quotation.getNano()).divide(BigDecimal.valueOf(1_000_000_000L), 9, RoundingMode.HALF_UP);
        return units.add(nano);
    }

    /**
     * НОВЫЙ МЕТОД: Конвертирует объект MoneyValue из API в BigDecimal.
     * @param moneyValue объект MoneyValue
     * @return BigDecimal представление суммы
     */
    public static BigDecimal moneyValueToBigDecimal(MoneyValue moneyValue) {
        if (moneyValue == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal units = BigDecimal.valueOf(moneyValue.getUnits());
        BigDecimal nano = BigDecimal.valueOf(moneyValue.getNano()).divide(BigDecimal.valueOf(1_000_000_000L), 9, RoundingMode.HALF_UP);
        return units.add(nano);
    }

    /**
     * Конвертирует BigDecimal в объект Quotation для отправки в API.
     * @param value BigDecimal значение
     * @return объект Quotation
     */
    public static Quotation bigDecimalToQuotation(BigDecimal value) {
        long units = value.longValue();
        int nano = value.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(1_000_000_000)).intValue();
        return Quotation.newBuilder().setUnits(units).setNano(nano).build();
    }

    /**
     * НОВЫЙ МЕТОД: Округляет цену до ближайшего шага.
     * @param price Цена для округления.
     * @param step Минимальный шаг цены.
     * @return Округленная цена.
     */
    public static BigDecimal roundToStep(BigDecimal price, BigDecimal step) {
        if (step == null || step.compareTo(BigDecimal.ZERO) == 0) {
            return price; // Не можем округлить, если шаг нулевой
        }
        return price.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }
}
