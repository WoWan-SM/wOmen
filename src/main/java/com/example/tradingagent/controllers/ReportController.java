package com.example.tradingagent.controllers;

import com.example.tradingagent.services.TinkoffReportService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final TinkoffReportService reportService;

    public ReportController(TinkoffReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/excel")
    public ResponseEntity<InputStreamResource> downloadExcelReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to) {

        try {
            // По умолчанию берем отчет за последние 7 дней
            Instant dateTo = to.orElse(Instant.now());
            Instant dateFrom = from.orElse(dateTo.minus(7, ChronoUnit.DAYS));

            ByteArrayInputStream in = reportService.generateOperationsReport(dateFrom, dateTo);

            HttpHeaders headers = new HttpHeaders();
            headers.add("Content-Disposition", "attachment; filename=trading_report.xlsx");

            return ResponseEntity
                    .ok()
                    .headers(headers)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(new InputStreamResource(in));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}