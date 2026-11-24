package com.bank.tm.app;

import com.bank.tm.core.Fixtures;
import com.bank.tm.core.domain.Decision;
import com.bank.tm.core.domain.LlmMode;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewStatus;
import com.bank.tm.core.llm.MockLlmClient;
import com.bank.tm.core.service.ReviewService;
import com.bank.tm.core.service.TmCore;
import com.bank.tm.core.service.TmSettings;
import com.bank.tm.core.service.Views;
import com.bank.tm.core.store.JdbcReviewStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uçtan uca akışı gerçek SQLite dosyası üzerinde, JdbcReviewStore ile çalıştırır. */
class JdbcStoreSqliteTest {

    @TempDir
    Path tmp;

    @Test
    void fullFlowPersistsInSqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("tm.db"));
        TmCore core = TmCore.create(new TmCore.Config(Fixtures.datasets(), tmp.resolve("uploads"),
                new JdbcReviewStore(ds, "sqlite"), new MockLlmClient(), null, Runnable::run, TmSettings.defaults(), 2,
                List.of(), List.of(), List.of()));

        Review r = core.reviews.createFromSample("tedarikci-fatura-entegrasyonu", "analist", LlmMode.SERVER, null);
        assertEquals(ReviewStatus.WAITING_MODEL_APPROVAL, core.reviews.detail(r.id()).review().status());
        core.reviews.approveModel(r.id(), "analist");
        List<Views.ThreatView> threats = core.reviews.threats(r.id());
        assertFalse(threats.isEmpty());
        for (Views.ThreatView t : threats) {
            core.reviews.updateThreat(r.id(), t.id(), new ReviewService.ThreatUpdate(Decision.ACCEPTED, null, null, null,
                    null, null, null, null, null), "analist");
        }
        core.reviews.completeExpertReview(r.id(), "analist");

        // Aynı veritabanı dosyasıyla yeni bir depo: veriler kalıcı olmalı
        JdbcReviewStore reopened = new JdbcReviewStore(ds, "sqlite");
        assertEquals(ReviewStatus.COMPLETED, reopened.findReview(r.id()).orElseThrow().status());
        assertEquals(threats.size(), reopened.listThreats(r.id()).size());
        assertTrue(reopened.listThreats(r.id()).stream().allMatch(t -> t.decision() == Decision.ACCEPTED));
        assertTrue(reopened.listLlmCalls(r.id()).size() >= 2);
        assertEquals(1, reopened.listModelVersions(r.id()).size());
        assertTrue(reopened.approvedModel(r.id()).isPresent());
    }

    @Test
    void settingsTablesAreAddedToExistingDatabaseAndPersist() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("old.db"));
        new JdbcReviewStore(ds, "sqlite");
        // Eski sürüm veritabanını taklit et: ayar tabloları yok
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("DROP TABLE app_setting_history");
            st.execute("DROP TABLE app_setting");
        }
        JdbcReviewStore store = new JdbcReviewStore(ds, "sqlite");
        store.putSetting("system", "{\"a\":1}", "admin", "ilk kayıt");
        store.putSetting("system", "{\"a\":2}", "admin", "a: 1 → 2");
        JdbcReviewStore reopened = new JdbcReviewStore(ds, "sqlite");
        assertEquals("{\"a\":2}", reopened.getSetting("system").orElseThrow());
        assertEquals(2, reopened.listSettingHistory("system", 10).size());
        assertEquals("a: 1 → 2", reopened.listSettingHistory("system", 10).get(0).summary());
    }
}
