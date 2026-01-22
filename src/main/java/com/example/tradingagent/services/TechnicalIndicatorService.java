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
            // ADXIndicator в ta4j требует минимум 2*period + 1 баров для первого валидного значения
            // Для периода 14: минимум 2*14 + 1 = 29 баров (первый валидный ADX на индексе 28)
            // Для стабильного значения рекомендуется иметь больше данных
            int adxPeriod = 14;
            int minCandlesForADX = 2 * adxPeriod + 1; // 29 минимум для первого валидного значения
            
            if (series.getBarCount() >= minCandlesForADX) {
                // Проверяем валидность данных перед расчетом ADX
                boolean hasValidData = true;
                int invalidBars = 0;
                int zeroRangeBars = 0; // Свечи с нулевым диапазоном (High == Low)
                
                for (int i = 0; i < series.getBarCount(); i++) {
                    var bar = series.getBar(i);
                    double high = bar.getHighPrice().doubleValue();
                    double low = bar.getLowPrice().doubleValue();
                    double close = bar.getClosePrice().doubleValue();
                    double open = bar.getOpenPrice().doubleValue();
                    
                    if (high < low || high <= 0 || low <= 0 || close <= 0 || open <= 0) {
                        invalidBars++;
                        hasValidData = false;
                    }
                    
                    // Проверяем нулевой диапазон (может вызвать проблемы с ADX)
                    if (high == low) {
                        zeroRangeBars++;
                    }
                }
                
                if (!hasValidData) {
                    logger.warn("Обнаружены некорректные данные для ADX {}: {} некорректных свечей из {}", 
                            ticker, invalidBars, series.getBarCount());
                }
                
                if (zeroRangeBars > series.getBarCount() * 0.1) { // Более 10% свечей с нулевым диапазоном
                    logger.warn("Много свечей с нулевым диапазоном для ADX {}: {} из {} ({}%). " +
                            "Это может привести к NaN в ADX.", 
                            ticker, zeroRangeBars, series.getBarCount(), 
                            (zeroRangeBars * 100 / series.getBarCount()));
                }
                
                ADXIndicator adx = new ADXIndicator(series, adxPeriod);
                
                // Согласно документации ta4j, первый валидный ADX появляется на индексе 2*period
                // Для периода 14 это индекс 28 (индексы 0-27 будут NaN)
                int minIndexForADX = 2 * adxPeriod; // 28 для периода 14
                
                // Пробуем найти валидное значение ADX, начиная с минимального индекса
                double adxValue = Double.NaN;
                int validAdxIndex = -1;
                
                // Проверяем индексы от минимального до последнего
                // Идем от последнего к минимальному, чтобы найти последнее валидное значение
                for (int i = lastIndex; i >= minIndexForADX; i--) {
                    try {
                        double testAdx = adx.getValue(i).doubleValue();
                        if (!Double.isNaN(testAdx) && !Double.isInfinite(testAdx) && testAdx >= 0 && testAdx <= 100) {
                            adxValue = testAdx;
                            validAdxIndex = i;
                            break; // Нашли последнее валидное значение, выходим
                        }
                    } catch (Exception e) {
                        // Игнорируем ошибки при получении значения для конкретного индекса
                        logger.debug("Ошибка при получении ADX для индекса {}: {}", i, e.getMessage());
                    }
                }
                
                // Если нашли валидное значение, используем его (предпочтительно последнее валидное)
                if (!Double.isNaN(adxValue) && validAdxIndex >= 0) {
                    indicators.put("adx_14", adxValue);
                    if (validAdxIndex < lastIndex) {
                        logger.debug("ADX для {}: использовано значение с индекса {} вместо последнего {}", 
                                ticker, validAdxIndex, lastIndex);
                    }
                } else {
                    // Детальная диагностика: проверяем данные для понимания проблемы
                    double totalRange = 0;
                    int rangeCount = 0;
                    int identicalHighLow = 0; // Свечи с High == Low
                    double minRange = Double.MAX_VALUE;
                    double maxRange = 0;
                    
                    // Проверяем последние 50 свечей для диагностики
                    int checkCount = Math.min(50, series.getBarCount());
                    for (int i = Math.max(0, series.getBarCount() - checkCount); i < series.getBarCount(); i++) {
                        var bar = series.getBar(i);
                        double high = bar.getHighPrice().doubleValue();
                        double low = bar.getLowPrice().doubleValue();
                        double range = high - low;
                        
                        if (range == 0) {
                            identicalHighLow++;
                        }
                        if (range < minRange) minRange = range;
                        if (range > maxRange) maxRange = range;
                        
                        double close = bar.getClosePrice().doubleValue();
                        if (close > 0) {
                            totalRange += range / close; // Нормализованный диапазон
                            rangeCount++;
                        }
                    }
                    double avgVolatility = rangeCount > 0 ? totalRange / rangeCount : 0;
                    
                    // Проверяем несколько образцов свечей для диагностики
                    StringBuilder barSamples = new StringBuilder();
                    for (int i = Math.max(0, lastIndex - 5); i <= lastIndex; i++) {
                        var bar = series.getBar(i);
                        barSamples.append(String.format("idx%d: H=%.2f L=%.2f C=%.2f O=%.2f R=%.4f; ", 
                                i, 
                                bar.getHighPrice().doubleValue(),
                                bar.getLowPrice().doubleValue(),
                                bar.getClosePrice().doubleValue(),
                                bar.getOpenPrice().doubleValue(),
                                bar.getHighPrice().doubleValue() - bar.getLowPrice().doubleValue()));
                    }
                    
                    // Пробуем получить значение ADX для нескольких индексов для диагностики
                    StringBuilder adxSamples = new StringBuilder();
                    for (int i = minIndexForADX; i <= Math.min(lastIndex, minIndexForADX + 10); i++) {
                        try {
                            double sample = adx.getValue(i).doubleValue();
                            if (Double.isNaN(sample)) {
                                adxSamples.append(String.format("idx%d=NaN ", i));
                            } else if (Double.isInfinite(sample)) {
                                adxSamples.append(String.format("idx%d=Inf ", i));
                            } else {
                                adxSamples.append(String.format("idx%d=%.2f ", i, sample));
                            }
                        } catch (Exception e) {
                            adxSamples.append(String.format("idx%d=ERR ", i));
                        }
                    }
                    
                    logger.warn("ADX для {} не может быть рассчитан. Свечей: {}, lastIndex: {}, minIndex: {}. " +
                            "Волатильность: avg={}, min={}, max={}. " +
                            "Свечей с High==Low: {} из {}. " +
                            "Образцы свечей: [{}]. " +
                            "Образцы ADX: [{}]. " +
                            "Возможные причины: недостаточная волатильность (слишком плоский рынок) или особенности расчета ADX в ta4j. " +
                            "ADX не будет добавлен в индикаторы - стратегия пропустит эту точку входа (ADX < 20.0).", 
                            ticker, series.getBarCount(), lastIndex, minIndexForADX,
                            String.format("%.4f", avgVolatility), 
                            String.format("%.4f", minRange), 
                            String.format("%.4f", maxRange),
                            identicalHighLow, checkCount,
                            barSamples.toString(),
                            adxSamples.toString());
                    // НЕ добавляем ADX в индикаторы - это явно указывает, что ADX не рассчитан
                    // Стратегия будет пропускать такие точки входа (adxObj == null || adx < 20.0)
                    // Это правильно для плоского рынка без тренда
                }
            } else {
                logger.warn("Недостаточно свечей для расчета ADX для {}: {} (требуется минимум {})", 
                        ticker, series.getBarCount(), minCandlesForADX);
                // Продолжаем без ADX, не возвращаем пустую карту
            }

            // ЛОГИРОВАНИЕ
            Double adxForLog = indicators.get("adx_14");
            String adxStr = adxForLog != null ? String.format("%.1f", adxForLog) : "N/A";
            String logMessage = String.format("CALC IND [%s]: Price=%.2f, ADX=%s, ATR=%.2f, MACD_Hist=%.4f",
                    ticker, currentPrice, adxStr, atrValue, macdHist);
            logger.info(logMessage);

        } catch (Exception e) {
            logger.error("Ошибка расчета индикаторов для {}: ", ticker, e);
        }

        return indicators;
    }
}