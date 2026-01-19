package com.example.tradingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

@SpringBootApplication
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
		System.out.println("----------------------------------------------------------\n");
	}
}