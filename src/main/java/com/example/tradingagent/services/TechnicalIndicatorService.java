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

    // Добавил ticker и figi для логирования
    public Map<String, Double> calculateIndicators(List<HistoricCandle> candles, String ticker, String figi) {
        Map<String, Double> indicators = new HashMap<>();
        if (candles == null || candles.size() < 120) {
            logger.warn("Недостаточно свечей для расчета индикаторов: {} (требуется минимум 120)", candles == null ? 0 : candles.size());
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
            double rsiPrevious = rsi.getValue(lastIndex - 1).doubleValue();
            
            // ВАЛИДАЦИЯ: Проверка на NaN, Infinity или невалидные значения
            if (Double.isNaN(rsiValue) || Double.isInfinite(rsiValue) || rsiValue <= 0 || rsiValue >= 100) {
                logger.error("Невалидный RSI для {}: {}", ticker, rsiValue);
                return indicators; // Возвращаем пустую карту
            }
            indicators.put("rsi_14", rsiValue);
            indicators.put("rsi_14_previous", rsiPrevious);

            // MACD
            MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
            EMAIndicator macdSignal = new EMAIndicator(macd, 9);
            double macdHist = macd.getValue(lastIndex).doubleValue() - macdSignal.getValue(lastIndex).doubleValue();
            double macdHistPrevious = macd.getValue(lastIndex - 1).doubleValue() - macdSignal.getValue(lastIndex - 1).doubleValue();
            
            if (Double.isNaN(macdHist) || Double.isInfinite(macdHist)) {
                logger.error("Невалидный MACD для {}: {}", ticker, macdHist);
                return indicators;
            }
            indicators.put("macd_hist", macdHist);
            indicators.put("macd_hist_previous", macdHistPrevious);

            // ATR (Волатильность)
            ATRIndicator atr = new ATRIndicator(series, 14);
            double atrValue = atr.getValue(lastIndex).doubleValue();
            
            if (Double.isNaN(atrValue) || Double.isInfinite(atrValue) || atrValue <= 0) {
                logger.error("Невалидный ATR для {}: {}", ticker, atrValue);
                return indicators;
            }
            indicators.put("atr_14", atrValue);

            // EMA & Price
            EMAIndicator ema100 = new EMAIndicator(closePrice, 100);
            double emaValue = ema100.getValue(lastIndex).doubleValue();
            double currentPrice = closePrice.getValue(lastIndex).doubleValue();
            
            if (Double.isNaN(currentPrice) || Double.isInfinite(currentPrice) || currentPrice <= 0) {
                logger.error("Невалидная цена для {}: {}", ticker, currentPrice);
                return indicators;
            }
            if (Double.isNaN(emaValue) || Double.isInfinite(emaValue)) {
                logger.warn("EMA100 NaN для {}, но продолжаем", ticker);
                // EMA может быть NaN на начальных периодах, но это не критично
            } else {
                indicators.put("ema_100", emaValue);
            }
            indicators.put("current_price", currentPrice);

            // ADX (Сила тренда)
            ADXIndicator adx = new ADXIndicator(series, 14);
            double adxValue = adx.getValue(lastIndex).doubleValue();
            
            // ВАЛИДАЦИЯ ADX: критически важный индикатор
            if (Double.isNaN(adxValue) || Double.isInfinite(adxValue) || adxValue < 0 || adxValue > 100) {
                logger.error("Невалидный ADX для {}: {} (NaN означает недостаточное количество свечей)", ticker, adxValue);
                return indicators; // Запрещаем торговлю при NaN в ADX
            }
            indicators.put("adx_14", adxValue);

            // ЛОГИРОВАНИЕ
            String logMessage = String.format("CALC IND [%s]: Price=%.2f, ADX=%.1f, ATR=%.2f, MACD_Hist=%.4f",
                    ticker, currentPrice, adxValue, atrValue, macdHist);
            logger.info(logMessage);

        } catch (Exception e) {
            logger.error("Ошибка расчета индикаторов для {}: ", ticker, e);
        }

        return indicators;
    }
}