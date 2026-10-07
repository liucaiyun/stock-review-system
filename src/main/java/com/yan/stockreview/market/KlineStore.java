package com.yan.stockreview.market;

import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.entity.KlineBarEntity;
import com.yan.stockreview.repository.KlineBarRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 日 K 持久化：内存缓存过期后 walk-forward / ATR 纪律线仍能用。 */
@Service
public class KlineStore {

    private final KlineBarRepository repository;

    public KlineStore(KlineBarRepository repository) {
        this.repository = repository;
    }

    public List<KlineBar> load(String secid) {
        if (secid == null || secid.isBlank()) {
            return List.of();
        }
        List<KlineBarEntity> rows = repository.findBySecidOrderByBarDateAsc(secid);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<KlineBar> out = new ArrayList<>(rows.size());
        for (KlineBarEntity e : rows) {
            out.add(toBar(e));
        }
        return out;
    }

    @Transactional
    public void save(String secid, List<KlineBar> bars) {
        if (secid == null || secid.isBlank() || bars == null || bars.isEmpty()) {
            return;
        }
        Map<String, KlineBarEntity> existing = new LinkedHashMap<>();
        for (KlineBarEntity row : repository.findBySecidOrderByBarDateAsc(secid)) {
            existing.put(row.getBarDate(), row);
        }
        List<KlineBarEntity> toSave = new ArrayList<>();
        for (KlineBar b : bars) {
            if (b == null || b.date() == null || b.date().isBlank()) {
                continue;
            }
            KlineBarEntity e = existing.getOrDefault(b.date(), new KlineBarEntity());
            e.setSecid(secid);
            e.setBarDate(b.date());
            e.setOpenPx(b.open());
            e.setHighPx(b.high());
            e.setLowPx(b.low());
            e.setClosePx(b.close());
            e.setVolume(b.volume());
            e.setAmount(b.amount());
            e.setPctChange(b.pctChange());
            e.setTurnover(b.turnover());
            toSave.add(e);
        }
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
    }

    private static KlineBar toBar(KlineBarEntity e) {
        return new KlineBar(
                e.getBarDate(),
                nz(e.getOpenPx()),
                nz(e.getClosePx()),
                nz(e.getHighPx()),
                nz(e.getLowPx()),
                nz(e.getVolume()),
                nz(e.getAmount()),
                nz(e.getPctChange()),
                nz(e.getTurnover()));
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }
}
