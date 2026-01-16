package com.example.tradingagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO для принятия торгового приказа от n8n.
 * Поля соответствуют JSON-объекту, который генерирует LLM.
 */
public class TradeRequest {

    private String action;

    @JsonProperty("instrument_figi")
    private String instrumentFigi;

    @JsonProperty("confidence_score")
    private double confidenceScore;

    // Getters and Setters
    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getInstrumentFigi() {
        return instrumentFigi;
    }

    public void setInstrumentFigi(String instrumentFigi) {
        this.instrumentFigi = instrumentFigi;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    @Override
    public String toString() {
        return "TradeRequest{" +
                "action='" + action + '\'' +
                ", instrumentFigi='" + instrumentFigi + '\'' +
                ", confidenceScore=" + confidenceScore +
                '}';
    }
}
