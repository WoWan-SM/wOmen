package com.example.tradingagent.controllers;

import com.example.tradingagent.entities.PositionEntity;
import com.example.tradingagent.entities.TradeDecision;
import com.example.tradingagent.repositories.PositionEntityRepository;
import com.example.tradingagent.repositories.TradeDecisionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API для просмотра статистики из базы данных.
 * Позволяет анализировать решения бота и результаты торговли.
 */
@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final TradeDecisionRepository tradeDecisionRepository;
    private final PositionEntityRepository positionRepository;

    @Autowired
    public StatisticsController(TradeDecisionRepository tradeDecisionRepository,
                                PositionEntityRepository positionRepository) {
        this.tradeDecisionRepository = tradeDecisionRepository;
        this.positionRepository = positionRepository;
    }

    /**
     * Получает статистику решений за период.
     */
    @GetMapping("/decisions")
    public ResponseEntity<?> getDecisionsStats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        try {
            Instant start = from != null ? Instant.parse(from) : Instant.now().minusSeconds(86400 * 7); // По умолчанию 7 дней
            Instant end = to != null ? Instant.parse(to) : Instant.now();

            List<TradeDecision> decisions = tradeDecisionRepository.findByTimestampBetween(start, end);

            Map<String, Object> stats = new HashMap<>();
            stats.put("total", decisions.size());
            stats.put("buy", decisions.stream().filter(d -> d.getDecision() == TradeDecision.DecisionType.BUY).count());
            stats.put("sell", decisions.stream().filter(d -> d.getDecision() == TradeDecision.DecisionType.SELL).count());
            stats.put("hold", decisions.stream().filter(d -> d.getDecision() == TradeDecision.DecisionType.HOLD).count());
            stats.put("ignore", decisions.stream().filter(d -> d.getDecision() == TradeDecision.DecisionType.IGNORE).count());

            // Статистика по причинам игнора
            Map<String, Long> ignoreReasons = new HashMap<>();
            decisions.stream()
                .filter(d -> d.getDecision() == TradeDecision.DecisionType.IGNORE)
                .forEach(d -> {
                    String reason = d.getReasonCode() != null ? d.getReasonCode() : "UNKNOWN";
                    ignoreReasons.put(reason, ignoreReasons.getOrDefault(reason, 0L) + 1);
                });
            stats.put("ignoreReasons", ignoreReasons);

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Ошибка: " + e.getMessage());
        }
    }

    /**
     * Получает статистику позиций (PnL).
     */
    @GetMapping("/positions")
    public ResponseEntity<?> getPositionsStats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        try {
            Instant start = from != null ? Instant.parse(from) : Instant.now().minusSeconds(86400 * 30); // По умолчанию 30 дней
            Instant end = to != null ? Instant.parse(to) : Instant.now();

            List<PositionEntity> positions = positionRepository.findByTimestampBetween(start, end)
                .stream()
                .filter(p -> p.getExitOrder() != null) // Только закрытые позиции
                .toList();

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalClosed", positions.size());

            int profitable = 0;
            int losing = 0;
            BigDecimal totalPnL = BigDecimal.ZERO;
            BigDecimal totalProfit = BigDecimal.ZERO;
            BigDecimal totalLoss = BigDecimal.ZERO;

            for (PositionEntity position : positions) {
                if (position.getPnlAbsolute() != null) {
                    totalPnL = totalPnL.add(position.getPnlAbsolute());
                    if (position.getPnlAbsolute().compareTo(BigDecimal.ZERO) > 0) {
                        profitable++;
                        totalProfit = totalProfit.add(position.getPnlAbsolute());
                    } else if (position.getPnlAbsolute().compareTo(BigDecimal.ZERO) < 0) {
                        losing++;
                        totalLoss = totalLoss.add(position.getPnlAbsolute());
                    }
                }
            }

            stats.put("profitable", profitable);
            stats.put("losing", losing);
            stats.put("totalPnL", totalPnL);
            stats.put("totalProfit", totalProfit);
            stats.put("totalLoss", totalLoss);
            stats.put("winRate", positions.size() > 0 ? (double) profitable / positions.size() * 100 : 0);

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Ошибка: " + e.getMessage());
        }
    }

    /**
     * Получает последние решения.
     */
    @GetMapping("/decisions/latest")
    public ResponseEntity<?> getLatestDecisions(@RequestParam(defaultValue = "50") int limit) {
        try {
            Instant start = Instant.now().minusSeconds(86400 * 7); // Последние 7 дней
            List<TradeDecision> decisions = tradeDecisionRepository.findByTimestampBetween(start, Instant.now())
                .stream()
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(limit)
                .toList();

            return ResponseEntity.ok(decisions);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Ошибка: " + e.getMessage());
        }
    }

    /**
     * Получает дневную статистику.
     */
    @GetMapping("/daily")
    public ResponseEntity<?> getDailyStats(@RequestParam(required = false) String date) {
        try {
            LocalDate targetDate = date != null ? LocalDate.parse(date) : LocalDate.now();
            Instant startOfDay = targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant endOfDay = targetDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

            // Статистика решений
            List<TradeDecision> decisions = tradeDecisionRepository.findByTimestampBetween(startOfDay, endOfDay);
            
            // Статистика позиций
            List<PositionEntity> positions = positionRepository.findByTimestampBetween(startOfDay, endOfDay)
                .stream()
                .filter(p -> p.getExitOrder() != null)
                .toList();

            Map<String, Object> stats = new HashMap<>();
            stats.put("date", targetDate.toString());
            stats.put("decisions", decisions.size());
            stats.put("closedPositions", positions.size());
            
            BigDecimal dailyPnL = BigDecimal.ZERO;
            for (PositionEntity position : positions) {
                if (position.getPnlAbsolute() != null) {
                    dailyPnL = dailyPnL.add(position.getPnlAbsolute());
                }
            }
            stats.put("dailyPnL", dailyPnL);

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Ошибка: " + e.getMessage());
        }
    }
}
