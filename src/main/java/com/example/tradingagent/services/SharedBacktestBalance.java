package com.example.tradingagent.services;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Потокобезопасный общий баланс для бэктеста всех тикеров.
 */
public class SharedBacktestBalance {
    
    private final AtomicReference<BigDecimal> availableBalance;
    private final AtomicReference<BigDecimal> lockedBalance;
    private final AtomicReference<BigDecimal> totalBalance;
    private final AtomicReference<BigDecimal> peakBalance;
    private final AtomicReference<BigDecimal> maxDrawdown;
    private final AtomicReference<BigDecimal> maxDrawdownPercent;
    
    public SharedBacktestBalance(BigDecimal initialBalance) {
        this.availableBalance = new AtomicReference<>(initialBalance);
        this.lockedBalance = new AtomicReference<>(BigDecimal.ZERO);
        this.totalBalance = new AtomicReference<>(initialBalance);
        this.peakBalance = new AtomicReference<>(initialBalance);
        this.maxDrawdown = new AtomicReference<>(BigDecimal.ZERO);
        this.maxDrawdownPercent = new AtomicReference<>(BigDecimal.ZERO);
    }
    
    /**
     * Синхронизированное открытие позиции
     */
    public synchronized boolean openPosition(BigDecimal tradeAmount, BigDecimal entryCommission) {
        BigDecimal currentAvailable = availableBalance.get();
        if (currentAvailable.compareTo(tradeAmount.add(entryCommission)) < 0) {
            return false; // Недостаточно средств
        }
        
        BigDecimal newAvailable = currentAvailable.subtract(tradeAmount).subtract(entryCommission);
        BigDecimal newLocked = lockedBalance.get().add(tradeAmount);
        BigDecimal newTotal = newAvailable.add(newLocked);
        
        availableBalance.set(newAvailable);
        lockedBalance.set(newLocked);
        totalBalance.set(newTotal);
        
        updatePeakAndDrawdown(newTotal);
        return true;
    }
    
    /**
     * Синхронизированное закрытие позиции
     */
    public synchronized void closePosition(BigDecimal lockedAmount, BigDecimal netPnL) {
        BigDecimal newAvailable = availableBalance.get().add(lockedAmount).add(netPnL);
        BigDecimal newLocked = lockedBalance.get().subtract(lockedAmount);
        BigDecimal newTotal = newAvailable.add(newLocked);
        
        availableBalance.set(newAvailable);
        lockedBalance.set(newLocked);
        totalBalance.set(newTotal);
        
        updatePeakAndDrawdown(newTotal);
    }
    
    /**
     * Синхронизированное обновление unrealized PnL
     */
    public synchronized void updateUnrealizedPnL(BigDecimal lockedAmount, BigDecimal unrealizedPnL) {
        BigDecimal currentAvailable = availableBalance.get();
        BigDecimal newTotal = currentAvailable.add(lockedAmount).add(unrealizedPnL);
        totalBalance.set(newTotal);
        updatePeakAndDrawdown(newTotal);
    }
    
    /**
     * Получить текущий доступный баланс
     */
    public BigDecimal getAvailableBalance() {
        return availableBalance.get();
    }
    
    /**
     * Получить текущий заблокированный баланс
     */
    public BigDecimal getLockedBalance() {
        return lockedBalance.get();
    }
    
    /**
     * Получить общий баланс
     */
    public BigDecimal getTotalBalance() {
        return totalBalance.get();
    }
    
    /**
     * Получить пиковый баланс
     */
    public BigDecimal getPeakBalance() {
        return peakBalance.get();
    }
    
    /**
     * Получить максимальную просадку
     */
    public BigDecimal getMaxDrawdown() {
        return maxDrawdown.get();
    }
    
    /**
     * Получить максимальную просадку в процентах
     */
    public BigDecimal getMaxDrawdownPercent() {
        return maxDrawdownPercent.get();
    }
    
    /**
     * Обновить пик и просадку
     */
    private void updatePeakAndDrawdown(BigDecimal currentTotal) {
        BigDecimal currentPeak = peakBalance.get();
        if (currentTotal.compareTo(currentPeak) > 0) {
            peakBalance.set(currentTotal);
            currentPeak = currentTotal;
        }
        
        BigDecimal drawdown = currentPeak.subtract(currentTotal);
        BigDecimal currentMaxDrawdown = maxDrawdown.get();
        if (drawdown.compareTo(currentMaxDrawdown) > 0) {
            maxDrawdown.set(drawdown);
            if (currentPeak.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal drawdownPercent = drawdown.divide(currentPeak, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                maxDrawdownPercent.set(drawdownPercent);
            }
        }
    }
}
