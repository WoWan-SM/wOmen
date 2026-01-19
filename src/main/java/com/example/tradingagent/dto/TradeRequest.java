package com.example.tradingagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TradeRequest {

    @JsonProperty("action")
    private String action;

    @JsonProperty("instrument_figi")
    private String instrumentFigi;

    @JsonProperty("ticker")
    private String ticker;

    @JsonProperty("confidence_score")
    private Double confidenceScore;

    @JsonProperty("reason")
    private String reason;

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

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public Double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}