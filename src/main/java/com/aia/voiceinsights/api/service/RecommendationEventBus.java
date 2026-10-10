package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.RecommendationEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One multicast {@link Sinks.Many} per in-flight recommendation run — this is
 * what turns each LangGraph4j node's start/complete callback into a live SSE
 * stream at GET /api/recommendations/{runId}/stream, driving the frontend's
 * agent orchestration animations in real time. A late subscriber (the SSE GET
 * arriving a moment after the POST kicked the run off) still sees every event
 * from the start, since each sink buffers on backpressure.
 */
@Service
public class RecommendationEventBus {

    private final Map<String, Sinks.Many<RecommendationEvent>> sinks = new ConcurrentHashMap<>();

    public Flux<RecommendationEvent> subscribe(String runId) {
        return sinkFor(runId).asFlux();
    }

    /**
     * Agents publish from several threads at once now that the three analysis agents run in parallel. A default
     * Reactor sink rejects (and silently drops) an emit that overlaps another, which would leave a card stuck on
     * "running" in the UI — so emits to one run's sink are serialized.
     */
    public void publish(String runId, RecommendationEvent event) {
        Sinks.Many<RecommendationEvent> sink = sinkFor(runId);
        synchronized (sink) {
            sink.tryEmitNext(event);
        }
    }

    /** Completes the run's stream and drops its sink — call once the run reaches a terminal state. */
    public void complete(String runId) {
        Sinks.Many<RecommendationEvent> sink = sinks.remove(runId);
        if (sink != null) {
            synchronized (sink) {
                sink.tryEmitComplete();
            }
        }
    }

    private Sinks.Many<RecommendationEvent> sinkFor(String runId) {
        return sinks.computeIfAbsent(runId, id -> Sinks.many().replay().all());
    }
}
