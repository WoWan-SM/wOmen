package com.example.tradingagent.repositories;

import com.example.tradingagent.entities.MarketSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshot, UUID> {
    List<MarketSnapshot> findByTickerOrderByTimestampDesc(String ticker);
    List<MarketSnapshot> findByTimestampBetween(Instant start, Instant end);
}
