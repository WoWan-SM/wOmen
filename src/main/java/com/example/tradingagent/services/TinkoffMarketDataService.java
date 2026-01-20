package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApi;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.GetOrderBookResponse;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;
import ru.tinkoff.piapi.core.InvestApi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Сервис для взаимодействия с MarketDataService Tinkoff API.
 * Отвечает за получение рыночных данных, таких как исторические свечи.
 */
@Service
public class TinkoffMarketDataService {

    private final InvestApi api;

    public TinkoffMarketDataService() {
        this.api = TinkoffApi.getApi();
    }

    /**
     * Получает исторические свечи для указанного инструмента.
     *
     * @param instrumentFigi FIGI (Financial Instrument Global Identifier) инструмента.
     * @param days           Количество прошедших дней, за которые нужно получить данные.
     * @param interval       Интервал свечей (например, 1 минута, 1 час).
     * @return Список объектов HistoricCandle.
     */
    public List<HistoricCandle> getHistoricCandles(String instrumentFigi, int days, CandleInterval interval, Instant to) {
        Instant from = to.minus(days, ChronoUnit.DAYS);

        try {
            // Используем синхронный вызов для простоты
            List<HistoricCandle> candles = api.getMarketDataService()
                    .getCandlesSync(instrumentFigi, from, to, interval);
            return candles;
        } catch (Exception e) {
            System.err.println("Ошибка при получении исторических свечей: " + e.getMessage());
            e.printStackTrace();
            // В реальном приложении здесь должна быть более сложная обработка ошибок
            return List.of(); // Возвращаем пустой список в случае ошибки
        }
    }

    /**
     * Получает данные биржевого стакана для инструмента.
     * @param instrumentFigi FIGI инструмента.
     * @return Объект OrderBook.
     */
    public GetOrderBookResponse getOrderBook(String instrumentFigi) {
        // Запрашиваем стакан глубиной 10 (10 лучших заявок на покупку и продажу)
        return api.getMarketDataService().getOrderBookSync(instrumentFigi, 10);
    }
}
