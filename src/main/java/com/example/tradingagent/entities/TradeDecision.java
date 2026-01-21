package com.example.tradingagent.entities;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Лог принятия решений - САМОЕ ВАЖНОЕ.
 * Записываем результат работы стратегии, даже если сделка не открылась.
 */
@Entity
@Table(name = "trade_decision")
public class TradeDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private MarketSnapshot snapshot;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DecisionType decision;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "reason_details", columnDefinition = "TEXT")
    private String reasonDetails;

    @Column(nullable = false)
    private Instant timestamp;

    public enum DecisionType {
        BUY,
        SELL,
        HOLD,
        IGNORE
    }

    public TradeDecision() {
    }

    public TradeDecision(MarketSnapshot snapshot, DecisionType decision, String reasonCode, String reasonDetails) {
        this.snapshot = snapshot;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.reasonDetails = reasonDetails;
        this.timestamp = Instant.now();
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public MarketSnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(MarketSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public DecisionType getDecision() {
        return decision;
    }

    public void setDecision(DecisionType decision) {
        this.decision = decision;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getReasonDetails() {
        return reasonDetails;
    }

    public void setReasonDetails(String reasonDetails) {
        this.reasonDetails = reasonDetails;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
