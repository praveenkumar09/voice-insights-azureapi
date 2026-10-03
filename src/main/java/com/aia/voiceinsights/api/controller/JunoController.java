package com.aia.voiceinsights.api.controller;

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
 * Juno's conversation brain. It only ever returns what Juno should say — there is deliberately no endpoint
 * here (or reachable from here) that starts a recommendation run: that remains an explicit action by the advisor.
 */
@RestController
@RequestMapping("/api/juno")
public class JunoController {

    private final JunoService juno;
    private final JunoSpeechService speech;

    public JunoController(JunoService juno, JunoSpeechService speech) {
        this.juno = juno;
        this.speech = speech;
    }

    /** Whether Juno can speak with the neural voice (otherwise the browser uses its own). */
    @org.springframework.web.bind.annotation.GetMapping(value = "/voice", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> voice() {
        return Map.of("enabled", speech.enabled(), "voices", speech.voices());
    }

    /** Speaks a line: MP3 audio. 503 when speech is not available, so the browser falls back to its own voice. */
    @PostMapping(value = "/speak", produces = "audio/mpeg")
    public ResponseEntity<byte[]> speak(@RequestBody Map<String, String> body) {
        byte[] audio = speech.speak(body.get("text"), body.get("voice"), body.get("tone"));
        if (audio == null) return ResponseEntity.status(503).build();
        return ResponseEntity.ok().header("Cache-Control", "private, max-age=3600").body(audio);
    }

    @PostMapping(value = "/turn", produces = MediaType.APPLICATION_JSON_VALUE)
    public JunoService.Response turn(@RequestBody JunoService.Request request) {
        return juno.next(request);
    }
}
