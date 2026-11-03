package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.service.AppSettingsService;
import com.aia.voiceinsights.api.service.BusinessUnits;
import com.aia.voiceinsights.api.service.Languages;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The business unit and languages the app is set up for. Everyone who is signed in reads them; the Admin page changes them. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    public record UnitView(String id, String name, String country, String currency, String symbol, double perSgd, String incomeExample,
                           String budgetExample, List<String> defaultLanguages) {}

    public record LanguageView(String code, String name) {}

    /** What is selected now, and everything an admin can choose from. */
    public record View(String businessUnit, UnitView unit, List<String> languages, String updatedAt, List<UnitView> units,
                       List<LanguageView> available, int minLanguages) {}

    private final AppSettingsService settings;

    public SettingsController(AppSettingsService settings) { this.settings = settings; }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public View get() { return view(settings.current()); }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        try {
            Object langs = body.get("languages");
            List<String> codes = langs instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            return ResponseEntity.ok(view(settings.save(String.valueOf(body.get("businessUnit")), codes)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static UnitView unit(BusinessUnits.Bu b) {
        return new UnitView(b.id(), b.name(), b.country(), b.currency(), b.symbol(), b.perSgd(), b.incomeExample(), b.budgetExample(), b.defaultLanguages());
    }

    private static View view(AppSettingsService.Settings s) {
        return new View(s.businessUnit(), unit(BusinessUnits.of(s.businessUnit())), s.languages(), s.updatedAt(),
                BusinessUnits.all().stream().map(SettingsController::unit).toList(),
                Languages.all().stream().map(l -> new LanguageView(l.code(), l.name().replaceAll(" \\(.*\\)", "").replace("Mandarin Chinese", "Mandarin"))).toList(),
                AppSettingsService.MIN_LANGUAGES);
    }
}
