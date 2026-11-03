package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.Campaign;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads a campaign poster ONCE, when it is uploaded, so that nothing about the poster is analysed while a customer is being advised.
 *
 * <p>The reading is done by a vision model looking at the picture itself (not OCR followed by text processing): it handles stylised type, text
 * on gradients and icons, which classic OCR does not, and it returns meaning as well as words. It returns the poster's text verbatim (kept for
 * the admin to check against), a summary, themes, benefits, the audience and dates it can see, and a set of short "customer signals": the sorts of
 * thing a customer or advisor might say that this campaign answers. Those signals, the overview and each benefit are embedded, and the matching at
 * debrief time compares the customer's words with them by meaning (see {@link CampaignAgentService}).
 *
 * <p>Nothing here publishes anything. The result is a DRAFT for an admin to review and edit; a campaign only reaches customers when an admin
 * activates it.
 */
@Service
public class CampaignAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CampaignAnalysisService.class);

    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    static final int MIN_SIDE = 300;
    static final int MAIN_SIDE = 1600;
    static final int THUMB_SIDE = 480;

    private static final String SYSTEM = """
            You read a marketing poster for an insurance company's advisors. Look at the whole image carefully and report ONLY what it shows.
            Never invent an offer, a number, a date, a partner or an eligibility rule that is not on the poster. If something is not legible or not
            there, leave it null or empty, and say so in "confidence".

            Respond with ONLY a JSON object, no markdown fences:
            {
              "name": "the programme or brand the poster promotes, as written (for example 'AIA Vitality'), not a small tag line or category label such as 'Healthy Living Campaign'; if the poster has no brand, its main headline; max 6 words",
              "summary": "what the campaign is and who it is for, in two plain sentences, max 45 words",
              "themes": ["3 to 6 short topics, 1-3 words each: e.g. fitness, health screening, family protection, rewards"],
              "benefits": ["each benefit or offer exactly as the poster states it, max 15 words each, up to 6"],
              "customerSignals": ["6 to 8 short statements a customer or advisor might make that this campaign answers, each 4-12 words, in plain English, covering different ways of saying it: e.g. 'wants to stay fit and exercise regularly', 'interested in gym and wellness', 'wants to track his health'"],
              "minAge": integer or null, "maxAge": integer or null, "audienceNote": "who may join, as stated, or null",
              "startsOn": "YYYY-MM-DD or null", "endsOn": "YYYY-MM-DD or null",
              "language": "the language the poster is written in, as a code: en, zh, ms, ta, th, fil, vi",
              "callToAction": "what the poster asks the reader to do, or null",
              "terms": "the small print or conditions as written, or null",
              "verbatimText": "every piece of text on the poster, in reading order, one line per block",
              "confidence": "high | medium | low: how sure you are that the fields above match the poster",
              "isCampaignPoster": true or false
            }
            Dates: read "1 Nov 2026 - 31 Jan 2027" as startsOn 2026-11-01 and endsOn 2027-01-31. If only an end date is given, startsOn is null.
            customerSignals describe what the CUSTOMER wants or is interested in (never a product pitch). Use "suggest", never "recommend".
            """;

    private final CampaignStore store;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddings;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "campaign-analysis");
        t.setDaemon(true);
        return t;
    });

    public CampaignAnalysisService(CampaignStore store, @Qualifier("localizingChatModel") ChatModel chatModel, EmbeddingModel embeddings) {
        this.store = store;
        this.chatModel = chatModel;
        this.embeddings = embeddings;
    }

    @PreDestroy
    void shutdown() { executor.shutdown(); }

    /** Thrown for a file the admin should fix (not an image, too small, too large); the message is shown to them. */
    public static class BadPoster extends RuntimeException {
        public BadPoster(String message) { super(message); }
    }

    /** Accepts an uploaded poster, stores it and starts reading it in the background. Returns the new campaign's id. */
    public String submit(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) throw new BadPoster("The file is empty.");
        if (bytes.length > MAX_IMAGE_BYTES) throw new BadPoster("The image is larger than " + MAX_IMAGE_BYTES / 1024 / 1024 + " MB. Please export a smaller one.");
        BufferedImage src = decode(bytes);
        if (Math.min(src.getWidth(), src.getHeight()) < MIN_SIDE) throw new BadPoster("The image is too small to read (at least " + MIN_SIDE + " pixels on each side).");
        byte[] main = jpeg(scaled(src, MAIN_SIDE), 0.9f);
        byte[] thumb = jpeg(scaled(src, THUMB_SIDE), 0.82f);
        String id = UUID.randomUUID().toString();
        store.create(id, nameFrom(filename), "image/jpeg", main, thumb);
        executor.submit(() -> analyse(id, main));
        return id;
    }

    /** Reads the stored poster again (after a failure, or when the admin asks). */
    public void reanalyse(String id) {
        var image = store.image(id, false).orElseThrow(() -> new BadPoster("This campaign has no poster image."));
        store.markAnalyzing(id);
        executor.submit(() -> analyse(id, image.data()));
    }

    void analyse(String id, byte[] image) {
        try {
            Campaign extracted = extract(image);
            List<CampaignStore.Vec> vectors = embed(id, extracted);
            store.saveAnalysis(id, extracted, vectors);
            log.info("Campaign {} read: \"{}\", {} themes, {} embeddings, confidence {}", id, extracted.name(), extracted.themes().size(), vectors.size(), extracted.confidence());
        } catch (Exception e) {
            log.warn("Campaign {}: the poster could not be read: {}", id, e.toString());
            store.markFailed(id, e instanceof BadPoster ? e.getMessage() : "The poster could not be read just now. Try again.");
        }
    }

    private Campaign extract(byte[] image) throws Exception {
        var response = chatModel.call(new Prompt(
                List.of(new SystemMessage(SYSTEM),
                        new UserMessage("Read this poster.", new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(image)))),
                AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.1).maxTokens(1800).build()));
        return parse(response.getResult().getOutput().getText());
    }

    /** Turns the model's JSON into a campaign draft, bounding every field. Package-private for tests. */
    Campaign parse(String json) throws Exception {
        JsonNode n = mapper.readTree(json);
        if (n.has("isCampaignPoster") && !n.path("isCampaignPoster").asBoolean(true)) throw new BadPoster("This image does not look like a campaign poster.");
        String name = text(n, "name", 80);
        String summary = text(n, "summary", 400);
        if (name == null || summary == null) throw new BadPoster("The poster could not be understood. Is the text readable?");
        List<String> signals = strings(n.path("customerSignals"), 8, 120);
        if (signals.isEmpty()) throw new BadPoster("The poster could not be understood. Is the text readable?");
        return new Campaign(null, name, Campaign.DRAFT, Campaign.READY, null, Wording.suggest(summary),
                strings(n.path("themes"), 6, 40), strings(n.path("benefits"), 6, 160).stream().map(Wording::suggest).toList(), signals,
                age(n, "minAge"), age(n, "maxAge"), text(n, "audienceNote", 200), date(n, "startsOn"), date(n, "endsOn"), List.of(),
                language(n.path("language").asText("")), text(n, "callToAction", 120), text(n, "terms", 600), text(n, "verbatimText", 4000),
                List.of("high", "medium", "low").contains(n.path("confidence").asText("")) ? n.path("confidence").asText() : "medium", true, null, null);
    }

    /** The embeddings of a campaign: its overview, each benefit and each customer signal. */
    List<CampaignStore.Vec> embed(String id, Campaign c) {
        List<String> kinds = new ArrayList<>(), labels = new ArrayList<>();
        kinds.add("overview");
        labels.add(c.name() + ". " + c.summary() + " Topics: " + String.join(", ", c.themes()) + ".");
        for (String b : c.benefits()) { kinds.add("benefit"); labels.add(b); }
        for (String s : c.customerSignals()) { kinds.add("signal"); labels.add(s); }
        List<float[]> vectors = embeddings.embed(labels);
        List<CampaignStore.Vec> out = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) out.add(new CampaignStore.Vec(id, kinds.get(i), labels.get(i), vectors.get(i)));
        return out;
    }

    /** The embeddings for an edited campaign: same as above, used when the admin changes what the campaign is about. */
    public List<CampaignStore.Vec> reembed(String id, Campaign c) { return embed(id, c); }

    // ── images ───────────────────────────────────────────────────────────────────────────────────────────────

    static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) throw new BadPoster("This file is not an image Spark can read. Please upload a PNG or JPG.");
            return img;
        } catch (java.io.IOException e) {
            throw new BadPoster("This file is not an image Spark can read. Please upload a PNG or JPG.");
        }
    }

    /** Scaled so the longer side is at most {@code max}, on a white background (a transparent PNG becomes a normal picture). */
    static BufferedImage scaled(BufferedImage src, int max) {
        double k = Math.min(1.0, (double) max / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * k)), h = Math.max(1, (int) Math.round(src.getHeight() * k));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static byte[] jpeg(BufferedImage img, float quality) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(img, null, null), p);
            } finally {
                writer.dispose();
            }
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String nameFrom(String filename) {
        String base = filename == null ? "" : filename.replaceAll("^.*[\\\\/]", "").replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[-_]+", " ").strip();
        return base.isEmpty() ? "New campaign" : base.substring(0, Math.min(80, base.length()));
    }

    // ── field helpers ────────────────────────────────────────────────────────────────────────────────────────

    private static String text(JsonNode n, String field, int max) {
        String v = n.path(field).isNull() ? "" : n.path(field).asText("").strip();
        return v.isEmpty() || "null".equalsIgnoreCase(v) ? null : v.substring(0, Math.min(max, v.length()));
    }

    private static List<String> strings(JsonNode arr, int maxItems, int maxLen) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (arr.isArray()) for (JsonNode x : arr) {
            String v = x.asText("").strip();
            if (!v.isEmpty()) out.add(v.substring(0, Math.min(maxLen, v.length())));
            if (out.size() == maxItems) break;
        }
        return List.copyOf(out);
    }

    private static Integer age(JsonNode n, String f) {
        if (!n.path(f).isNumber()) return null;
        int a = n.path(f).asInt();
        return a >= 0 && a <= 120 ? a : null;
    }

    private static String date(JsonNode n, String f) {
        String v = text(n, f, 10);
        if (v == null) return null;
        try { return LocalDate.parse(v).toString(); } catch (DateTimeParseException e) { return null; }
    }

    private static List<String> language(String code) {
        String c = code.toLowerCase();
        return Languages.isSupported(c) ? List.of(c) : List.of();
    }
}
