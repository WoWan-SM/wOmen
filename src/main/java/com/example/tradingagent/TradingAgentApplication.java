package com.example.tradingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradingAgentApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(TradingAgentApplication.class, args);
		Environment env = context.getEnvironment();
		String port = env.getProperty("server.port", "8080");
		String contextPath = env.getProperty("server.servlet.context-path", "");

		System.out.println("\n----------------------------------------------------------");
		System.out.println("\tApplication is running! Access URLs:");
		System.out.println("\tLocal: \t\thttp://localhost:" + port + contextPath);
		System.out.println("\tScan Market: \thttp://localhost:" + port + contextPath + "/api/scan-market");
		System.out.println("\tExcel Report: \thttp://localhost:" + port + contextPath + "/api/reports/excel");
		System.out.println("\tStatistics: \thttp://localhost:" + port + contextPath + "/api/statistics/decisions");
		System.out.println("\tPositions: \thttp://localhost:" + port + contextPath + "/api/statistics/positions");
		System.out.println("\tDaily Stats: \thttp://localhost:" + port + contextPath + "/api/statistics/daily");
		System.out.println("----------------------------------------------------------\n");
	}
}