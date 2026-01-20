package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApi;
import com.example.tradingagent.TinkoffApiUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.Operation;
import ru.tinkoff.piapi.contract.v1.OperationType;
import ru.tinkoff.piapi.core.InvestApi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TinkoffReportService {

    private final InvestApi api;
    private final String accountId;

    public TinkoffReportService(TinkoffAccountService accountService) {
        this.api = TinkoffApi.getApi();
        this.accountId = accountService.getSandboxAccountId();
    }

    // --- МОДЕЛИ ДАННЫХ ---

    private static class RawOperation {
        Instant time;
        String ticker;
        OperationType type;
        BigDecimal price;
        long quantity;
        BigDecimal amount;
        BigDecimal commission;
        String currency;
        String figi;
        boolean isStandaloneFee; // Флаг для маржиналки и прочих комиссий без сделки
    }

    // Общий интерфейс для строки отчета (Сделка или Комиссия)
    private interface ReportItem {
        Instant getTime();
        LocalDate getDate();
    }

    private static class ClosedTrade implements ReportItem {
        String ticker;
        Instant openTime;
        Instant closeTime;
        String direction;
        BigDecimal openPrice;
        BigDecimal closePrice;
        long quantity;
        BigDecimal totalCommission;
        BigDecimal netPnL; // Чистая прибыль
        String resultType; // "Прибыль" или "Убыток"

        @Override public Instant getTime() { return closeTime; }
        @Override public LocalDate getDate() { return closeTime.atZone(ZoneId.systemDefault()).toLocalDate(); }
    }

    private static class StandaloneFee implements ReportItem {
        Instant time;
        String ticker;
        String description; // "Маржинальная комиссия" и т.д.
        BigDecimal amount; // Отрицательное число

        @Override public Instant getTime() { return time; }
        @Override public LocalDate getDate() { return time.atZone(ZoneId.systemDefault()).toLocalDate(); }
    }

    private static class Stats {
        int totalTrades = 0;
        int profitableTrades = 0;
        int losingTrades = 0;

        BigDecimal grossProfit = BigDecimal.ZERO; // Прибыль со сделок
        BigDecimal grossLoss = BigDecimal.ZERO;   // Убыток со сделок (отрицательный)
        BigDecimal tradingCommission = BigDecimal.ZERO; // Комиссия внутри сделок
        BigDecimal marginCommission = BigDecimal.ZERO;  // Маржиналка и прочее

        BigDecimal totalNetPnL = BigDecimal.ZERO; // Итоговый результат
    }

    // --- ОСНОВНОЙ МЕТОД ---

    public ByteArrayInputStream generateOperationsReport(Instant from, Instant to) throws IOException {
        List<Operation> operations = api.getOperationsService().getExecutedOperationsSync(
                accountId, from, to
        );

        List<RawOperation> rawOps = processAndMergeFees(operations);
        List<ReportItem> allItems = reconstructHistory(rawOps);

        return generateExcel(allItems);
    }

    // --- ЛОГИКА EXCEL ---

    private ByteArrayInputStream generateExcel(List<ReportItem> items) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle currencyStyle = createCurrencyStyle(workbook);
            CellStyle profitStyle = createCurrencyStyle(workbook, false, IndexedColors.GREEN.getIndex());
            CellStyle lossStyle = createCurrencyStyle(workbook, false, IndexedColors.RED.getIndex());
            CellStyle boldStyle = createBoldStyle(workbook);

            // Группировка по дням
            Map<LocalDate, List<ReportItem>> itemsByDate = items.stream()
                    .collect(Collectors.groupingBy(
                            ReportItem::getDate,
                            () -> new TreeMap<>(Collections.reverseOrder()),
                            Collectors.toList()
                    ));

            // --- ЛИСТ 1: ОБЩАЯ СТАТИСТИКА ---
            createGlobalSummarySheet(workbook, items, headerStyle, currencyStyle, profitStyle, lossStyle);

            // --- ДНЕВНЫЕ ЛИСТЫ ---
            DateTimeFormatter dateTimeFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

            for (Map.Entry<LocalDate, List<ReportItem>> entry : itemsByDate.entrySet()) {
                String sheetName = entry.getKey().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
                Sheet sheet = workbook.createSheet(sheetName);

                String[] columns = {
                        "Дата/Время", "Тикер", "Тип / Напр.",
                        "Цена вх.", "Цена вых.", "Кол-во",
                        "Комиссия", "P&L (Чистый)", "Результат"
                };

                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < columns.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(columns[i]);
                    cell.setCellStyle(headerStyle);
                }

                int rowIdx = 1;
                Stats dailyStats = new Stats();

                // Сортировка: новые сверху
                List<ReportItem> dailyItems = entry.getValue();
                dailyItems.sort((i1, i2) -> i2.getTime().compareTo(i1.getTime()));

                for (ReportItem item : dailyItems) {
                    Row row = sheet.createRow(rowIdx++);

                    if (item instanceof ClosedTrade t) {
                        updateStats(dailyStats, t);

                        row.createCell(0).setCellValue(dateTimeFmt.format(t.closeTime));
                        row.createCell(1).setCellValue(t.ticker);
                        row.createCell(2).setCellValue(t.direction);
                        createCell(row, 3, t.openPrice.doubleValue(), currencyStyle);
                        createCell(row, 4, t.closePrice.doubleValue(), currencyStyle);
                        row.createCell(5).setCellValue(t.quantity);
                        createCell(row, 6, t.totalCommission.doubleValue(), lossStyle);

                        CellStyle pnlStyle = t.netPnL.compareTo(BigDecimal.ZERO) >= 0 ? profitStyle : lossStyle;
                        createCell(row, 7, t.netPnL.doubleValue(), pnlStyle);

                        Cell resCell = row.createCell(8);
                        resCell.setCellValue(t.resultType);
                        resCell.setCellStyle(pnlStyle);

                    } else if (item instanceof StandaloneFee f) {
                        updateStats(dailyStats, f);

                        row.createCell(0).setCellValue(dateTimeFmt.format(f.time));
                        row.createCell(1).setCellValue(f.ticker);
                        row.createCell(2).setCellValue(f.description); // Например "Маржинальная комиссия"

                        // Пустые ячейки цен/количества
                        row.createCell(3).setBlank();
                        row.createCell(4).setBlank();
                        row.createCell(5).setBlank();

                        createCell(row, 6, f.amount.abs().doubleValue(), lossStyle); // Комиссия
                        createCell(row, 7, f.amount.doubleValue(), lossStyle);       // PnL (отрицательный)

                        Cell resCell = row.createCell(8);
                        resCell.setCellValue("Списание");
                        resCell.setCellStyle(lossStyle);
                    }
                }

                rowIdx++;
                createDailySummary(sheet, rowIdx, dailyStats, boldStyle, profitStyle, lossStyle);
                for (int i = 0; i < columns.length; i++) sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    private List<ReportItem> reconstructHistory(List<RawOperation> rawOps) {
        rawOps.sort(Comparator.comparing(o -> o.time)); // FIFO

        List<ReportItem> history = new ArrayList<>();
        Map<String, Deque<RawOperation>> openPositions = new HashMap<>();

        for (RawOperation op : rawOps) {
            // 1. Маржинальные и отдельные комиссии сразу добавляем в историю
            if (op.isStandaloneFee || op.quantity == 0) {
                StandaloneFee fee = new StandaloneFee();
                fee.time = op.time;
                fee.ticker = op.ticker;
                fee.amount = op.amount.add(op.commission.negate()); // amount у комиссии отрицательный, commission положительная (мы ее инвертируем)
                // Обычно у комиссии amount < 0.
                fee.amount = op.amount; // Берем payment напрямую

                if (op.type == OperationType.OPERATION_TYPE_MARGIN_FEE) fee.description = "Маржинальная ком.";
                else if (op.type == OperationType.OPERATION_TYPE_BROKER_FEE) fee.description = "Брокерская ком.";
                else fee.description = "Прочая комиссия";

                history.add(fee);
                continue;
            }

            // 2. Обработка сделок (Trade Reconstruction)
            String ticker = op.ticker;
            Deque<RawOperation> queue = openPositions.computeIfAbsent(ticker, k -> new ArrayDeque<>());
            boolean isLong = (op.type == OperationType.OPERATION_TYPE_BUY || op.type == OperationType.OPERATION_TYPE_BUY_CARD);

            if (queue.isEmpty() || isSameDirection(queue.peek(), isLong)) {
                queue.add(op); // Открытие или добавление позиции
            } else {
                // Закрытие позиции
                long quantityToClose = op.quantity;
                while (quantityToClose > 0 && !queue.isEmpty()) {
                    RawOperation openOp = queue.peek();
                    long matchedQty = Math.min(openOp.quantity, quantityToClose);

                    ClosedTrade trade = new ClosedTrade();
                    trade.ticker = ticker;
                    trade.openTime = openOp.time;
                    trade.closeTime = op.time;
                    trade.direction = (openOp.type == OperationType.OPERATION_TYPE_BUY) ? "LONG" : "SHORT";
                    trade.quantity = matchedQty;
                    trade.openPrice = openOp.price;
                    trade.closePrice = op.price;

                    // Комиссия (пропорционально)
                    BigDecimal openCommPerUnit = openOp.quantity > 0 ? openOp.commission.divide(BigDecimal.valueOf(openOp.quantity), 10, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                    BigDecimal currentOpenComm = openCommPerUnit.multiply(BigDecimal.valueOf(matchedQty));

                    BigDecimal closeCommPerUnit = op.quantity > 0 ? op.commission.divide(BigDecimal.valueOf(op.quantity), 10, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                    BigDecimal currentCloseComm = closeCommPerUnit.multiply(BigDecimal.valueOf(matchedQty));

                    trade.totalCommission = currentOpenComm.add(currentCloseComm);

                    BigDecimal priceDiff = trade.closePrice.subtract(trade.openPrice);
                    if ("SHORT".equals(trade.direction)) priceDiff = priceDiff.negate();

                    BigDecimal grossPnL = priceDiff.multiply(BigDecimal.valueOf(matchedQty));
                    trade.netPnL = grossPnL.subtract(trade.totalCommission);

                    // Исправленные метки
                    if (trade.netPnL.compareTo(BigDecimal.ZERO) > 0) trade.resultType = "Прибыль";
                    else trade.resultType = "Убыток";

                    history.add(trade);

                    quantityToClose -= matchedQty;
                    openOp.quantity -= matchedQty;
                    openOp.commission = openOp.commission.subtract(currentOpenComm);

                    if (openOp.quantity == 0) queue.poll();
                }
            }
        }
        return history;
    }

    private void updateStats(Stats stats, ReportItem item) {
        if (item instanceof ClosedTrade t) {
            stats.totalTrades++;
            if (t.netPnL.compareTo(BigDecimal.ZERO) > 0) {
                stats.profitableTrades++;
                stats.grossProfit = stats.grossProfit.add(t.netPnL.add(t.totalCommission)); // Gross
            } else {
                stats.losingTrades++;
                stats.grossLoss = stats.grossLoss.add(t.netPnL.add(t.totalCommission)); // Gross
            }
            stats.totalNetPnL = stats.totalNetPnL.add(t.netPnL);
            stats.tradingCommission = stats.tradingCommission.add(t.totalCommission);
        } else if (item instanceof StandaloneFee f) {
            stats.marginCommission = stats.marginCommission.add(f.amount.abs());
            stats.totalNetPnL = stats.totalNetPnL.add(f.amount); // amount отрицательный
        }
    }

    private void createGlobalSummarySheet(Workbook workbook, List<ReportItem> items, CellStyle header, CellStyle cur, CellStyle profit, CellStyle loss) {
        Sheet sheet = workbook.createSheet("ОБЩАЯ СТАТИСТИКА");
        Stats stats = new Stats();
        items.forEach(i -> updateStats(stats, i));

        int r = 1;
        createStatRow(sheet, r++, "Всего закрытых сделок:", (double) stats.totalTrades, header, cur);
        createStatRow(sheet, r++, "Прибыльных сделок:", (double) stats.profitableTrades, header, profit);
        createStatRow(sheet, r++, "Убыточных сделок:", (double) stats.losingTrades, header, loss);

        r++;
        createStatRow(sheet, r++, "Комиссии (Торговля):", stats.tradingCommission.doubleValue(), header, loss);
        createStatRow(sheet, r++, "Комиссии (Маржа и др.):", stats.marginCommission.doubleValue(), header, loss);
        BigDecimal totalComm = stats.tradingCommission.add(stats.marginCommission);
        createStatRow(sheet, r++, "ВСЕГО КОМИССИЙ:", totalComm.doubleValue(), header, loss);

        r++;
        CellStyle netStyle = stats.totalNetPnL.compareTo(BigDecimal.ZERO) >= 0 ? profit : loss;
        createStatRow(sheet, r++, "ЧИСТЫЙ РЕЗУЛЬТАТ:", stats.totalNetPnL.doubleValue(), header, netStyle);

        sheet.setColumnWidth(0, 8000);
        sheet.setColumnWidth(1, 5000);
    }

    private void createDailySummary(Sheet sheet, int startRow, Stats stats, CellStyle bold, CellStyle profit, CellStyle loss) {
        Row row = sheet.createRow(startRow);
        row.createCell(2).setCellValue("ИТОГО:");
        row.getCell(2).setCellStyle(bold);

        // Сумма комиссий (Торговая + Маржинальная)
        BigDecimal totalComm = stats.tradingCommission.add(stats.marginCommission);
        Cell commCell = row.createCell(6);
        commCell.setCellValue(totalComm.doubleValue());
        commCell.setCellStyle(loss);

        Cell pnlCell = row.createCell(7);
        pnlCell.setCellValue(stats.totalNetPnL.doubleValue());
        pnlCell.setCellStyle(stats.totalNetPnL.compareTo(BigDecimal.ZERO) >= 0 ? profit : loss);
    }

    // --- ПРЕДОБРАБОТКА (Слияние) ---
    private List<RawOperation> processAndMergeFees(List<Operation> sourceOps) {
        List<Operation> sorted = new ArrayList<>(sourceOps);
        sorted.sort(Comparator.comparing(o -> o.getDate().getSeconds()));

        List<RawOperation> result = new ArrayList<>();
        Map<String, List<Operation>> feePool = new HashMap<>();

        // Сбор комиссий
        for (Operation op : sorted) {
            if (op.getOperationType() == OperationType.OPERATION_TYPE_BROKER_FEE) {
                feePool.computeIfAbsent(op.getFigi(), k -> new ArrayList<>()).add(op);
            }
        }

        for (Operation op : sorted) {
            OperationType type = op.getOperationType();

            // Торговые операции
            if (type == OperationType.OPERATION_TYPE_BUY || type == OperationType.OPERATION_TYPE_SELL) {
                RawOperation raw = new RawOperation();
                raw.time = Instant.ofEpochSecond(op.getDate().getSeconds());
                raw.ticker = getTickerByFigi(op.getFigi());
                raw.figi = op.getFigi();
                raw.type = type;
                raw.quantity = op.getQuantity();
                raw.price = TinkoffApiUtils.moneyValueToBigDecimal(op.getPrice());
                raw.amount = TinkoffApiUtils.moneyValueToBigDecimal(op.getPayment());
                raw.currency = op.getCurrency();
                raw.isStandaloneFee = false;

                BigDecimal comm = BigDecimal.ZERO;
                if (feePool.containsKey(raw.figi)) {
                    Iterator<Operation> it = feePool.get(raw.figi).iterator();
                    while (it.hasNext()) {
                        Operation fee = it.next();
                        long diff = Math.abs(fee.getDate().getSeconds() - raw.time.getEpochSecond());
                        if (diff <= 30) {
                            comm = comm.add(TinkoffApiUtils.moneyValueToBigDecimal(fee.getPayment()));
                            it.remove();
                        }
                    }
                }
                raw.commission = comm.abs();
                result.add(raw);
            }
            // Маржиналка и прочие комиссии
            else if (type == OperationType.OPERATION_TYPE_MARGIN_FEE ||
                    type == OperationType.OPERATION_TYPE_SERVICE_FEE) {
                RawOperation raw = new RawOperation();
                raw.time = Instant.ofEpochSecond(op.getDate().getSeconds());
                raw.ticker = getTickerByFigi(op.getFigi());
                raw.type = type;
                raw.amount = TinkoffApiUtils.moneyValueToBigDecimal(op.getPayment());
                raw.commission = BigDecimal.ZERO;
                raw.quantity = 0;
                raw.isStandaloneFee = true;
                result.add(raw);
            }
        }

        // Добавляем оставшиеся "осиротевшие" брокерские комиссии
        for (List<Operation> fees : feePool.values()) {
            for (Operation fee : fees) {
                RawOperation raw = new RawOperation();
                raw.time = Instant.ofEpochSecond(fee.getDate().getSeconds());
                raw.ticker = getTickerByFigi(fee.getFigi());
                raw.type = OperationType.OPERATION_TYPE_BROKER_FEE;
                raw.amount = TinkoffApiUtils.moneyValueToBigDecimal(fee.getPayment());
                raw.commission = BigDecimal.ZERO;
                raw.quantity = 0;
                raw.isStandaloneFee = true;
                result.add(raw);
            }
        }
        return result;
    }

    // --- HELPER METHODS ---
    private boolean isSameDirection(RawOperation queueOp, boolean isIncomingLong) {
        boolean isQueueLong = (queueOp.type == OperationType.OPERATION_TYPE_BUY);
        return isQueueLong == isIncomingLong;
    }

    private void createStatRow(Sheet sheet, int rowIdx, String label, Double value, CellStyle labelStyle, CellStyle valStyle) {
        Row row = sheet.createRow(rowIdx);
        Cell l = row.createCell(0); l.setCellValue(label); l.setCellStyle(labelStyle);
        Cell v = row.createCell(1); v.setCellValue(value); v.setCellStyle(valStyle);
    }

    private void createCell(Row row, int idx, double val, CellStyle style) {
        Cell c = row.createCell(idx); c.setCellValue(val); c.setCellStyle(style);
    }

    private String getTickerByFigi(String figi) {
        if (figi == null || figi.isEmpty()) return "-";
        try { return api.getInstrumentsService().getInstrumentByFigiSync(figi).getTicker(); } catch (Exception e) { return figi; }
    }

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font); style.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER); return style;
    }

    private CellStyle createBoldStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle(); Font font = wb.createFont(); font.setBold(true); style.setFont(font); return style;
    }

    private CellStyle createCurrencyStyle(Workbook wb, boolean isRed, short colorIndex) {
        CellStyle style = wb.createCellStyle(); DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        if (isRed || colorIndex != 0) {
            Font font = wb.createFont(); font.setColor(colorIndex != 0 ? colorIndex : IndexedColors.RED.getIndex()); style.setFont(font);
        }
        return style;
    }

    private CellStyle createCurrencyStyle(Workbook wb) { return createCurrencyStyle(wb, false, (short)0); }
}