package com.bank.tm.app.web;

import com.bank.tm.app.config.TmProperties;
import com.bank.tm.core.service.TmCore;
import com.bank.tm.core.settings.SettingsService;
import com.bank.tm.core.settings.SystemSettings;
import com.bank.tm.core.store.ReviewStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sistem parametreleri: LLM / embedding bağlantıları, adım bazında model, tarayıcı LLM'i, OCR ve akış ayarları.
 * Okuma herkese açıktır (anahtarlar maskeli); değişiklik tm.security.settings-admins listesindeki kullanıcılarla sınırlıdır.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final TmCore core;
    private final List<String> admins;

    public SettingsController(TmCore core, TmProperties props) {
        this.core = core;
        this.admins = props.security() == null || props.security().settingsAdmins() == null ? List.of() : props.security().settingsAdmins();
    }

    @GetMapping
    public Map<String, Object> get(@RequestHeader(value = "X-User", required = false) String user) {
        SettingsService.View v = core.systemSettings.view();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", v.settings());
        m.put("secrets", v.secrets());
        m.put("policy", v.policy());
        m.put("source", v.source());
        m.put("canEdit", canEdit(CurrentUser.of(user)));
        m.put("adminsConfigured", !admins.isEmpty());
        m.put("active", Map.of("llm", core.llm.serverClient().name() + " · " + core.llm.serverClient().model(),
                "retrieval", core.retriever.mode()));
        return m;
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody SystemSettings draft, @RequestHeader(value = "X-User", required = false) String user) {
        String actor = requireAdmin(user);
        core.systemSettings.update(draft, actor);
        return get(user);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestHeader(value = "X-User", required = false) String user) {
        core.systemSettings.resetToDefaults(requireAdmin(user));
        return get(user);
    }

    /** Kaydetmeden bağlantı testi (taslak değerlerle). target: llm | embedding */
    @PostMapping("/test")
    public List<SettingsService.TestResult> test(@RequestParam("target") String target, @RequestBody SystemSettings draft,
                                                 @RequestHeader(value = "X-User", required = false) String user) {
        requireAdmin(user);
        return core.systemSettings.test(draft, target);
    }

    @GetMapping("/history")
    public List<ReviewStore.SettingChange> history(@RequestParam(value = "limit", defaultValue = "30") int limit) {
        return core.systemSettings.history(Math.min(Math.max(limit, 1), 200));
    }

    private boolean canEdit(String user) {
        return admins.isEmpty() || admins.contains(user);
    }

    private String requireAdmin(String header) {
        String user = CurrentUser.of(header);
        if (!canEdit(user)) throw new ForbiddenException("Sistem parametrelerini değiştirme yetkiniz yok (" + user + ")");
        return user;
    }
}
