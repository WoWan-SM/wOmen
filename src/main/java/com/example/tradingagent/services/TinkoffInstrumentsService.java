package com.example.tradingagent.services;

import com.example.tradingagent.TinkoffApi;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.InstrumentStatus;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.core.InvestApi;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TinkoffInstrumentsService {

    private final InvestApi api;

    // ИЗМЕНЕНО: Список расширен до 15 инструментов
    private final List<String> blueChipsTickers = List.of(
            // Старые
            "ROSN",
            "GAZP",
            "SBER",
            "GMKN",
            "ROSN",
            "ALRS",
            "NLMK",
            "SNGSP",
            "TATN",
            "SNGS",
            "VTBR",
            "VKCO",
            "PIKK",
            "AFLT",
            "AFKS",
            "MOEX",
            "MAGN",
            "RTKM",
            "SVCB",
            "RUAL",
            "UPRO",
            "IRAO",
            "UGLD",
            "BSPB",
            "MTSS",
            "FEES"
    );

    public TinkoffInstrumentsService() {
        this.api = TinkoffApi.getApi();
    }

    /**
     * Возвращает полные данные по инструментам для нашего списка.
     * @return Список объектов Share.
     */
    public List<Share> getBlueChips() {
        return api.getInstrumentsService().getSharesSync(InstrumentStatus.INSTRUMENT_STATUS_BASE)
                .stream()
                .filter(share -> blueChipsTickers.contains(share.getTicker()))
                .collect(Collectors.toList());
    }
}
