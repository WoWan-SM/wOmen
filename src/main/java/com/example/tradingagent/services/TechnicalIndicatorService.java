package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TechnicalIndicatorService {

    private static final Logger logger = LoggerFactory.getLogger(TechnicalIndicatorService.class);

    public Map<String, Double> calculateIndicators(List<HistoricCandle> candles) {
        Map<String, Double> indicators = new HashMap<>();
        if (candles == null || candles.size() < 120) {
            return indicators;
        }

        try {
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

            // RSI
            RSIIndicator rsi = new RSIIndicator(closePrice, 14);
            double rsiValue = rsi.getValue(lastIndex).doubleValue();
            indicators.put("rsi_14", rsiValue);
            indicators.put("rsi_14_previous", rsi.getValue(lastIndex - 1).doubleValue());

            // MACD
            MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
            EMAIndicator macdSignal = new EMAIndicator(macd, 9);
            double macdHist = macd.getValue(lastIndex).doubleValue() - macdSignal.getValue(lastIndex).doubleValue();
            indicators.put("macd_hist", macdHist);
            indicators.put("macd_hist_previous", macd.getValue(lastIndex - 1).doubleValue() - macdSignal.getValue(lastIndex - 1).doubleValue());

            // ATR
            ATRIndicator atr = new ATRIndicator(series, 14);
            double atrValue = atr.getValue(lastIndex).doubleValue();
            indicators.put("atr_14", atrValue);

            // EMA & Price
            EMAIndicator ema100 = new EMAIndicator(closePrice, 100);
            double emaValue = ema100.getValue(lastIndex).doubleValue();
            double priceValue = closePrice.getValue(lastIndex).doubleValue();
            indicators.put("ema_100", emaValue);
            indicators.put("current_price", priceValue);

            // ADX
            ADXIndicator adx = new ADXIndicator(series, 14);
            double adxValue = adx.getValue(lastIndex).doubleValue();
            indicators.put("adx_14", adxValue);

            // Логирование критических значений для отладки стратегии
            // Если ADX низкий, но сигнал идет - это причина убытков во флэте
             logger.info("Calculated Ind: Price={}, EMA={}, MACD_Hist={}, ADX={}, ATR={}",
                    String.format("%.2f", priceValue), String.format("%.2f", emaValue),
                    String.format("%.4f", macdHist), String.format("%.2f", adxValue), String.format("%.2f", atrValue));

        } catch (Exception e) {
            logger.error("Ошибка расчета индикаторов: ", e);
        }

        return indicators;
    }
}