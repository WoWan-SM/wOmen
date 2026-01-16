package com.example.tradingagent;

import ru.tinkoff.piapi.core.InvestApi;

public class TinkoffApi {

    private static InvestApi instance;

    // Приватный конструктор, чтобы нельзя было создать экземпляр извне
    private TinkoffApi() {}

    public static synchronized InvestApi getApi() {
        if (instance == null) {
            String token = System.getenv("TINKOFF_API_TOKEN");
            if (token == null || token.isEmpty()) {
                throw new IllegalStateException("Не установлена переменная окружения TINKOFF_API_TOKEN");
            }
            // Для начала настроим приложение на использование эндпоинта песочницы [cite: 223]
            // Когда будем переходить к реальной торговле, мы это изменим
            System.setProperty("tinkoff.invest.host", "sandbox-invest-public-api.tinkoff.ru");
            instance = InvestApi.create(token);
        }
        return instance;
    }
}