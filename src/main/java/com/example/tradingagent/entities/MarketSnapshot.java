package com.example.tradingagent.entities;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Снимок рынка - записывается каждый раз, когда бот анализирует тикер.
 */
@Entity
@Table(name = "market_snapshot")
public class MarketSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(name = "price_close", precision = 19, scale = 4)
    private BigDecimal priceClose;

    @Column(name = "rsi_value", precision = 10, scale = 4)
    private BigDecimal rsiValue;

    @Column(name = "adx_value", precision = 10, scale = 4)
    private BigDecimal adxValue;

    @Column(name = "macd_value", precision = 19, scale = 8)
    private BigDecimal macdValue;

    @Column(name = "atr_value", precision = 19, scale = 8)
    private BigDecimal atrValue;

    @Column(name = "ema_value", precision = 19, scale = 4)
    private BigDecimal emaValue;

    @Column(name = "current_price", precision = 19, scale = 4)
    private BigDecimal currentPrice;

    public MarketSnapshot() {
    }

    public MarketSnapshot(Instant timestamp, String ticker, BigDecimal priceClose) {
        this.timestamp = timestamp;
        this.ticker = ticker;
        this.priceClose = priceClose;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public BigDecimal getPriceClose() {
        return priceClose;
    }

    public void setPriceClose(BigDecimal priceClose) {
        this.priceClose = priceClose;
    }

    public BigDecimal getRsiValue() {
        return rsiValue;
    }

    public void setRsiValue(BigDecimal rsiValue) {
        this.rsiValue = rsiValue;
    }

    public BigDecimal getAdxValue() {
        return adxValue;
    }

    public void setAdxValue(BigDecimal adxValue) {
        this.adxValue = adxValue;
    }

    public BigDecimal getMacdValue() {
        return macdValue;
    }

    public void setMacdValue(BigDecimal macdValue) {
        this.macdValue = macdValue;
    }

    public BigDecimal getAtrValue() {
        return atrValue;
    }

    public void setAtrValue(BigDecimal atrValue) {
        this.atrValue = atrValue;
    }

    public BigDecimal getEmaValue() {
        return emaValue;
    }

    public void setEmaValue(BigDecimal emaValue) {
        this.emaValue = emaValue;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }
}
