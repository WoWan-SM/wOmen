package com.example.tradingagent.controllers;

import com.example.tradingagent.entities.MarketSnapshot;
import com.example.tradingagent.entities.PositionEntity;
import com.example.tradingagent.entities.TradeDecision;
import com.example.tradingagent.repositories.MarketSnapshotRepository;
import com.example.tradingagent.repositories.PositionEntityRepository;
import com.example.tradingagent.repositories.TradeDecisionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API для Dashboard - предоставляет данные для визуализации в реальном времени.
 * Может использоваться с Grafana или простым фронтендом.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final MarketSnapshotRepository snapshotRepository;
    private final TradeDecisionRepository decisionRepository;
    private final PositionEntityRepository positionRepository;

    @Autowired
    public DashboardController(MarketSnapshotRepository snapshotRepository,
                         TradeDecisionRepository decisionRepository,
                         PositionEntityRepository positionRepository) {
        this.snapshotRepository = snapshotRepository;
        this.decisionRepository = decisionRepository;
        this.positionRepository = positionRepository;
    }

    /**
     * Получает общую статистику для dashboard
     */
    @GetMapping("/overview")
    public ResponseEntity<?> getOverview(
            @RequestParam(defaultValue = "24") int hours) {
        try {
            Instant from = Instant.now().minusSeconds(hours * 3600L);
            
            // Статистика решений
            List<TradeDecision> decisions = decisionRepository.findByTimestampAfter(from);
            long buyDecisions = decisions.stream()
                    .filter(d -> d.getDecision() == TradeDecision.DecisionType.BUY)
                    .count();
            long sellDecisions = decisions.stream()
                    .filter(d -> d.getDecision() == TradeDecision.DecisionType.SELL)
                    .count();
            long holdDecisions = decisions.stream()
                    .filter(d -> d.getDecision() == TradeDecision.DecisionType.HOLD)
                    .count();
            long ignoreDecisions = decisions.stream()
                    .filter(d -> d.getDecision() == TradeDecision.DecisionType.IGNORE)
                    .count();
            
            // Статистика позиций
            List<PositionEntity> positions = positionRepository.findByTimestampAfter(from);
            BigDecimal totalPnL = positions.stream()
                    .map(PositionEntity::getPnlAbsolute)
                    .filter(pnl -> pnl != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            long closedPositions = positions.stream()
                    .filter(p -> p.getExitOrder() != null)
                    .count();
            
            long winningTrades = positions.stream()
                    .filter(p -> p.getPnlAbsolute() != null && p.getPnlAbsolute().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            
            // Последние снимки рынка
            List<MarketSnapshot> recentSnapshots = snapshotRepository.findTop10ByOrderByTimestampDesc();
            
            Map<String, Object> response = new HashMap<>();
            response.put("periodHours", hours);
            response.put("decisions", Map.of(
                    "total", decisions.size(),
                    "buy", buyDecisions,
                    "sell", sellDecisions,
                    "hold", holdDecisions,
                    "ignore", ignoreDecisions
            ));
            response.put("positions", Map.of(
                    "total", positions.size(),
                    "closed", closedPositions,
                    "winning", winningTrades,
                    "totalPnL", totalPnL
            ));
            response.put("recentSnapshots", recentSnapshots.stream()
                    .map(s -> Map.of(
                            "ticker", s.getTicker(),
                            "timestamp", s.getTimestamp(),
                            "price", s.getPriceClose(),
                            "adx", s.getAdxValue(),
                            "rsi", s.getRsiValue()
                    ))
                    .collect(Collectors.toList()));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Ошибка при получении данных: " + e.getMessage());
        }
    }

    /**
     * Получает данные для графика баланса (PnL по времени)
     */
    @GetMapping("/balance-chart")
    public ResponseEntity<?> getBalanceChart(
            @RequestParam(defaultValue = "7") int days) {
        try {
            Instant from = Instant.now().minusSeconds(days * 86400L);
            List<PositionEntity> positions = positionRepository.findByTimestampAfter(from);
            
            // Группируем по дням
            Map<String, BigDecimal> dailyPnL = positions.stream()
                    .filter(p -> p.getExitOrder() != null && p.getPnlAbsolute() != null)
                    .collect(Collectors.groupingBy(
                            p -> p.getTimestamp().toString().substring(0, 10),
                            Collectors.reducing(BigDecimal.ZERO, PositionEntity::getPnlAbsolute, BigDecimal::add)
                    ));
            
            return ResponseEntity.ok(dailyPnL);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Ошибка при получении данных: " + e.getMessage());
        }
    }

    /**
     * Получает открытые позиции
     */
    @GetMapping("/open-positions")
    public ResponseEntity<?> getOpenPositions() {
        try {
            List<PositionEntity> openPositions = positionRepository.findByExitOrderIsNull();
            
            List<Map<String, Object>> positions = openPositions.stream()
                    .map(p -> {
                        Map<String, Object> pos = new HashMap<>();
                        pos.put("id", p.getId());
                        pos.put("entryTime", p.getTimestamp());
                        pos.put("entryPrice", p.getEntryPrice());
                        pos.put("currentPnL", p.getPnlAbsolute());
                        pos.put("currentPnLPercent", p.getPnlPercent());
                        return pos;
                    })
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(positions);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Ошибка при получении данных: " + e.getMessage());
        }
    }
}
