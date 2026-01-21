package com.example.tradingagent.repositories;

import com.example.tradingagent.entities.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderEntityRepository extends JpaRepository<OrderEntity, UUID> {
    Optional<OrderEntity> findByBrokerOrderId(String brokerOrderId);
    List<OrderEntity> findByTicker(String ticker);
    List<OrderEntity> findByTickerAndDirection(String ticker, OrderEntity.Direction direction);
}
