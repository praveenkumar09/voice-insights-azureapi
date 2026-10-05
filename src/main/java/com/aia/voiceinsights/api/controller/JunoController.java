package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.service.DebriefFollowUpService;
import com.aia.voiceinsights.api.service.JunoDebriefService;
import com.aia.voiceinsights.api.service.JunoService;
import com.aia.voiceinsights.api.service.JunoSpeechService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Juno's conversation brain (hosting a customer, or debriefing an advisor). It only ever returns what Juno should say — there is deliberately no endpoint
 * here (or reachable from here) that starts a recommendation run: that remains an explicit action by the advisor.
 */
@RestController
@RequestMapping("/api/juno")
public class JunoController {

    private final JunoService juno;
    private final JunoDebriefService debrief;
    private final DebriefFollowUpService followUp;
    private final JunoSpeechService speech;

    public JunoController(JunoService juno, JunoDebriefService debrief, DebriefFollowUpService followUp, JunoSpeechService speech) {
        this.juno = juno;
        this.debrief = debrief;
        this.followUp = followUp;
        this.speech = speech;
    }

    /** Whether Juno can speak with the neural voice (otherwise the browser uses its own). */
    @org.springframework.web.bind.annotation.GetMapping(value = "/voice", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> voice() {
        return Map.of("enabled", speech.enabled(), "voices", speech.voices());
    }

    /** Speaks a line: MP3 audio. 503 when speech is not available, so the browser falls back to its own voice. */
    /** The fixed lines Juno says in a language (greeting, consent questions, goodbyes, acknowledgements). */
    @org.springframework.web.bind.annotation.GetMapping(value = "/phrases", produces = MediaType.APPLICATION_JSON_VALUE)
    public com.aia.voiceinsights.api.service.JunoPhrases.Phrases phrases(@org.springframework.web.bind.annotation.RequestParam(value = "lang", required = false) String lang) {
        return com.aia.voiceinsights.api.service.JunoPhrases.of(lang);
    }

    @PostMapping(value = "/speak", produces = "audio/mpeg")
    public ResponseEntity<byte[]> speak(@RequestBody Map<String, String> body) {
        byte[] audio = speech.speak(body.get("text"), body.get("voice"), body.get("tone"), body.get("lang"));
        if (audio == null) return ResponseEntity.status(503).build();
        return ResponseEntity.ok().header("Cache-Control", "private, max-age=3600").body(audio);
    }

    /** Debrief with Juno: what Juno says to the advisor next, after the advisor's dictation. */
    @PostMapping(value = "/debrief-turn", produces = MediaType.APPLICATION_JSON_VALUE)
    public JunoService.Response debriefTurn(@RequestBody JunoDebriefService.Request request) {
        return debrief.next(request);
    }

    /** Warms what Juno needs for its first line while the advisor's last words are still being transcribed. */
    @PostMapping(value = "/debrief-prepare")
    public ResponseEntity<Void> debriefPrepare(@RequestBody Map<String, String> body) {
        debrief.prepare(body.get("profileId"));
        return ResponseEntity.accepted().build();
    }

    /** The follow-up message the advisor can send the customer after a debrief (a draft, in the chosen language). Nothing is sent. */
    @PostMapping(value = "/debrief-followup", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> debriefFollowUp(@RequestBody Map<String, String> body) {
        return followUp.draft(body.get("profileId"), body.get("lang"))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(502).body(Map.of("error", "Could not draft the follow-up")));
    }

    @PostMapping(value = "/turn", produces = MediaType.APPLICATION_JSON_VALUE)
    public JunoService.Response turn(@RequestBody JunoService.Request request) {
        return juno.next(request);
    }
}
