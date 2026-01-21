package com.example.tradingagent.entities;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Учет ордеров (исполнение).
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "broker_order_id", length = 100)
    private String brokerOrderId;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Direction direction;

    @Column(name = "requested_price", precision = 19, scale = 4)
    private BigDecimal requestedPrice;

    @Column(name = "executed_price", precision = 19, scale = 4)
    private BigDecimal executedPrice;

    @Column(name = "quantity_lots")
    private Long quantityLots;

    @Column(precision = 19, scale = 4)
    private BigDecimal commission;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private Instant timestamp;

    public enum Direction {
        BUY,
        SELL
    }

    public enum OrderStatus {
        NEW,
        FILLED,
        REJECTED,
        CANCELLED
    }

    public OrderEntity() {
    }

    public OrderEntity(String ticker, Direction direction, BigDecimal requestedPrice, Long quantityLots) {
        this.ticker = ticker;
        this.direction = direction;
        this.requestedPrice = requestedPrice;
        this.quantityLots = quantityLots;
        this.status = OrderStatus.NEW;
        this.timestamp = Instant.now();
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getBrokerOrderId() {
        return brokerOrderId;
    }

    public void setBrokerOrderId(String brokerOrderId) {
        this.brokerOrderId = brokerOrderId;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public BigDecimal getRequestedPrice() {
        return requestedPrice;
    }

    public void setRequestedPrice(BigDecimal requestedPrice) {
        this.requestedPrice = requestedPrice;
    }

    public BigDecimal getExecutedPrice() {
        return executedPrice;
    }

    public void setExecutedPrice(BigDecimal executedPrice) {
        this.executedPrice = executedPrice;
    }

    public Long getQuantityLots() {
        return quantityLots;
    }

    public void setQuantityLots(Long quantityLots) {
        this.quantityLots = quantityLots;
    }

    public BigDecimal getCommission() {
        return commission;
    }

    public void setCommission(BigDecimal commission) {
        this.commission = commission;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
