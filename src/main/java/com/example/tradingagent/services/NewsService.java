package com.example.tradingagent.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Service
public class NewsService {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String sessionId = System.getenv("TINKOFF_SESSION_ID");

    public List<String> getRecentNewsHeadlines(String ticker) {
        if (sessionId == null || sessionId.isBlank()) {
            System.err.println("Переменная окружения TINKOFF_SESSION_ID не установлена. Анализ новостей по тикеру пропускается.");
            return List.of();
        }
        // ИСПРАВЛЕНО: Добавлен параметр &include=interesting
        String url = "https://api-invest-gw.tinkoff.ru/social/post/feed/v1/post/instrument/" + ticker + "?limit=2&include=interesting";
        return fetchPulseFeed(url, "Ticker " + ticker);
    }

    public List<String> getGeneralRussianNews() {
        if (sessionId == null || sessionId.isBlank()) {
            System.err.println("Переменная окружения TINKOFF_SESSION_ID не установлена. Анализ общих новостей пропускается.");
            return List.of();
        }
        String url = "https://api-invest-gw.tinkoff.ru/social/post/feed/v1/feed?limit=5";
        return fetchPulseFeed(url, "General Feed");
    }

    private List<String> fetchPulseFeed(String url, String identifier) {
        System.out.println("ЗАПРОС К TINKOFF PULSE API: " + identifier);
        List<String> content = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "&appName=invest_terminal&appVersion=2.0.0&platform=invest_terminal&origin=web,ib5,platform,mdep&sessionId=" + sessionId))
                    .header("accept", "*/*")
                    .header("accept-language", "ru,en;q=0.9")
                    .header("origin", "https://www.tbank.ru")
                    .header("referer", "https://www.tbank.ru/")
                    .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36")
                    .header("x-app-name", "invest_terminal")
                    .header("x-app-version", "2.0.0")
                    .header("x-platform", "invest_terminal")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                if (response.statusCode() == 202) {
                    System.out.println("INFO: Tinkoff Pulse API вернул статус 202 для '" + identifier + "'. Новости для этого инструмента недоступны. Продолжаем без них.");
                } else {
                    System.err.println("Ошибка от Tinkoff Pulse API: " + response.statusCode() + " " + response.body());
                }
                return content;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode items = root.path("payload").path("items");

            if (items.isArray()) {
                for (JsonNode item : items) {
                    content.add(item.path("content").path("text").asText());
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка при получении данных из Pulse для '" + identifier + "': " + e.getMessage());
        }
        return content;
    }
}
