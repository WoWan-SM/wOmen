package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApi;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.Account;
import ru.tinkoff.piapi.core.InvestApi;

import java.util.List;

@Service
public class TinkoffAccountService {
    private final InvestApi api;

    public TinkoffAccountService() {
        this.api = TinkoffApi.getApi();
    }

    /**
     * Получает список всех доступных счетов.
     * @return List<UserAccount>
     */
    public List<Account> getAccounts() {
        return api.getUserService().getAccountsSync();
    }

    /**
     * Находит первый попавшийся "боевой" счет (не ИИС).
     * В песочнице это будет наш единственный счет.
     * @return ID счета.
     */
    public String getSandboxAccountId() {
        List<Account> accounts = getAccounts();
        if (accounts.isEmpty()) {
            throw new IllegalStateException("Не найдено ни одного счета.");
        }
        // В песочнице обычно один счет, берем его
        return accounts.get(0).getId();
    }
}
