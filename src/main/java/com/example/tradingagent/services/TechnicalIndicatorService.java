package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApiUtils;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис для расчета технических индикаторов.
 */
@Service
public class TechnicalIndicatorService {

    /**
     * Рассчитывает набор индикаторов для предоставленного списка свечей.
     * @param candles Список исторических свечей.
     * @return Map с последними значениями индикаторов.
     */
    public Map<String, Double> calculateIndicators(List<HistoricCandle> candles) {
        Map<String, Double> indicators = new HashMap<>();
        if (candles == null || candles.size() < 110) {
            return indicators;
        }

        BarSeries series = new BaseBarSeries("candles");
        for (HistoricCandle candle : candles) {
            ZonedDateTime dateTime = ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochSecond(candle.getTime().getSeconds(), candle.getTime().getNanos()),
                    ZoneId.systemDefault()
            );
            series.addBar(
                    dateTime,
                    TinkoffApiUtils.quotationToBigDecimal(candle.getOpen()),
                    TinkoffApiUtils.quotationToBigDecimal(candle.getHigh()),
                    TinkoffApiUtils.quotationToBigDecimal(candle.getLow()),
                    TinkoffApiUtils.quotationToBigDecimal(candle.getClose()),
                    BigDecimal.valueOf(candle.getVolume())
            );
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        int lastIndex = series.getEndIndex();

        // Расчет RSI
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        indicators.put("rsi_14", rsi.getValue(lastIndex).doubleValue());
        indicators.put("rsi_14_previous", rsi.getValue(lastIndex - 1).doubleValue());

        // Расчет MACD
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);

        double macdLineValue = macd.getValue(lastIndex).doubleValue();
        double macdSignalValue = macdSignal.getValue(lastIndex).doubleValue();
        double macdHistValue = macdLineValue - macdSignalValue;
        indicators.put("macd_hist", macdHistValue);

        // ДОБАВЛЕНО: Расчет предыдущего значения гистограммы MACD
        double prevMacdLineValue = macd.getValue(lastIndex - 1).doubleValue();
        double prevMacdSignalValue = macdSignal.getValue(lastIndex - 1).doubleValue();
        indicators.put("macd_hist_previous", prevMacdLineValue - prevMacdSignalValue);

        // Расчет ATR
        ATRIndicator atr = new ATRIndicator(series, 14);
        indicators.put("atr_14", atr.getValue(lastIndex).doubleValue());

        // Расчет EMA для фильтра тренда
        EMAIndicator ema100 = new EMAIndicator(closePrice, 100);
        indicators.put("ema_100", ema100.getValue(lastIndex).doubleValue());
        indicators.put("current_price", closePrice.getValue(lastIndex).doubleValue());

        return indicators;
    }
}
