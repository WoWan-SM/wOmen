package com.example.tradingagent.entities;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Учет позиций для отслеживания PnL.
 */
@Entity
@Table(name = "positions")
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_order_id")
    private OrderEntity entryOrder;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exit_order_id")
    private OrderEntity exitOrder;

    @Column(name = "entry_price", precision = 19, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 19, scale = 4)
    private BigDecimal exitPrice;

    @Column(name = "pnl_absolute", precision = 19, scale = 4)
    private BigDecimal pnlAbsolute;

    @Column(name = "pnl_percent", precision = 10, scale = 4)
    private BigDecimal pnlPercent;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(nullable = false)
    private Instant timestamp;

    public PositionEntity() {
    }

    public PositionEntity(OrderEntity entryOrder, BigDecimal entryPrice) {
        this.entryOrder = entryOrder;
        this.entryPrice = entryPrice;
        this.timestamp = Instant.now();
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public OrderEntity getEntryOrder() {
        return entryOrder;
    }

    public void setEntryOrder(OrderEntity entryOrder) {
        this.entryOrder = entryOrder;
    }

    public OrderEntity getExitOrder() {
        return exitOrder;
    }

    public void setExitOrder(OrderEntity exitOrder) {
        this.exitOrder = exitOrder;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(BigDecimal entryPrice) {
        this.entryPrice = entryPrice;
    }

    public BigDecimal getExitPrice() {
        return exitPrice;
    }

    public void setExitPrice(BigDecimal exitPrice) {
        this.exitPrice = exitPrice;
    }

    public BigDecimal getPnlAbsolute() {
        return pnlAbsolute;
    }

    public void setPnlAbsolute(BigDecimal pnlAbsolute) {
        this.pnlAbsolute = pnlAbsolute;
    }

    public BigDecimal getPnlPercent() {
        return pnlPercent;
    }

    public void setPnlPercent(BigDecimal pnlPercent) {
        this.pnlPercent = pnlPercent;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
