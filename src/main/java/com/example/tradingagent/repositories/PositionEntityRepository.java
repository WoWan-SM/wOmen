package com.example.tradingagent.repositories;

import com.example.tradingagent.entities.PositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PositionEntityRepository extends JpaRepository<PositionEntity, UUID> {
    List<PositionEntity> findByExitOrderIsNull();
    List<PositionEntity> findByTimestampBetween(Instant start, Instant end);
}
