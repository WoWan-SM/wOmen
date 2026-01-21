package com.example.tradingagent.repositories;

import com.example.tradingagent.entities.TradeDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TradeDecisionRepository extends JpaRepository<TradeDecision, UUID> {
    List<TradeDecision> findByTimestampBetween(Instant start, Instant end);
    List<TradeDecision> findByDecision(TradeDecision.DecisionType decision);
}
