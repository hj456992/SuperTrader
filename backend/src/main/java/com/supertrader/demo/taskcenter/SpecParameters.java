package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed, versioned StrategySpec parameter union.
 *
 * <p>The six-argument constructor and its flat JSON shape are the legacy
 * task-center.v1 SMA contract. New parameter objects carry schemaVersion=2 and
 * an explicit type discriminator. Unknown keys are rejected by the delegating
 * creator even when a caller's ObjectMapper would otherwise ignore them.
 */
public final class SpecParameters {

    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final String SIGNAL_BAR_CLOSE = "BAR_CLOSE";
    public static final String SIGNAL_EVENT_AVAILABLE = "EVENT_AVAILABLE";
    public static final String FILL_NEXT_BAR_OPEN = "NEXT_BAR_OPEN";

    public record SmaCross(Integer fastWindow, Integer slowWindow,
                           Double positionSize, Double stopLossPct,
                           Integer feeBps, Integer slippageBps) {}

    public record PriceBreakout(Integer lookbackBars, String direction,
                                Integer entryOffsetTicks, Double stopLossPct,
                                Double positionSize, Integer feeBps,
                                Integer slippageBps) {}

    public record EventSignal(String eventId, String sourceId, String contentHash,
                              String originalPublishedAt, String fetchedAt,
                              String availableAt, String validFrom, String validUntil,
                              String relatedInstrument, String direction,
                              String confirmationId, String confirmedByMemberId,
                              String confirmedAt, String sourceStatus,
                              Double stopLossPct, Double positionSize,
                              Integer feeBps, Integer slippageBps) {}

    private static final Set<String> LEGACY_SMA_KEYS = Set.of(
            "fastWindow", "slowWindow", "positionSize", "stopLossPct",
            "feeBps", "slippageBps");
    private static final Set<String> COMMON_V2_KEYS = Set.of(
            "schemaVersion", "type", "signalAt", "fillAt");
    private static final Set<String> SMA_V2_KEYS = union(COMMON_V2_KEYS, LEGACY_SMA_KEYS);
    private static final Set<String> BREAKOUT_KEYS = union(COMMON_V2_KEYS, Set.of(
            "lookbackBars", "direction", "entryOffsetTicks", "stopLossPct",
            "positionSize", "feeBps", "slippageBps"));
    private static final Set<String> EVENT_KEYS = union(COMMON_V2_KEYS, Set.of(
            "eventId", "sourceId", "contentHash", "originalPublishedAt", "fetchedAt",
            "availableAt", "validFrom", "validUntil", "relatedInstrument",
            "direction", "confirmationId", "confirmedByMemberId", "confirmedAt",
            "sourceStatus", "stopLossPct", "positionSize", "feeBps", "slippageBps"));

    private final int schemaVersion;
    private final String type;
    private final boolean legacy;
    private final SmaCross sma;
    private final PriceBreakout breakout;
    private final EventSignal event;
    private final String signalAt;
    private final String fillAt;

    /** Legacy task-center.v1 constructor; its serialized shape is unchanged. */
    public SpecParameters(Integer fastWindow, Integer slowWindow,
                          Double positionSize, Double stopLossPct,
                          Integer feeBps, Integer slippageBps) {
        this(1, StrategySpecDraft.TEMPLATE_SMA_CROSS, true,
                new SmaCross(fastWindow, slowWindow, positionSize, stopLossPct,
                        feeBps, slippageBps), null, null,
                SIGNAL_BAR_CLOSE, FILL_NEXT_BAR_OPEN);
    }

    private SpecParameters(int schemaVersion, String type, boolean legacy,
                           SmaCross sma, PriceBreakout breakout, EventSignal event,
                           String signalAt, String fillAt) {
        this.schemaVersion = schemaVersion;
        this.type = type;
        this.legacy = legacy;
        this.sma = sma;
        this.breakout = breakout;
        this.event = event;
        this.signalAt = signalAt;
        this.fillAt = fillAt;
    }

    public static SpecParameters smaV2(Integer fastWindow, Integer slowWindow,
                                       Double positionSize, Double stopLossPct,
                                       Integer feeBps, Integer slippageBps) {
        return new SpecParameters(CURRENT_SCHEMA_VERSION,
                StrategySpecDraft.TEMPLATE_SMA_CROSS, false,
                new SmaCross(fastWindow, slowWindow, positionSize, stopLossPct,
                        feeBps, slippageBps), null, null,
                SIGNAL_BAR_CLOSE, FILL_NEXT_BAR_OPEN);
    }

    public static SpecParameters breakout(Integer lookbackBars, String direction,
                                          Integer entryOffsetTicks, Double stopLossPct,
                                          Double positionSize, Integer feeBps,
                                          Integer slippageBps) {
        return new SpecParameters(CURRENT_SCHEMA_VERSION,
                StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT, false, null,
                new PriceBreakout(lookbackBars, direction, entryOffsetTicks,
                        stopLossPct, positionSize, feeBps, slippageBps), null,
                SIGNAL_BAR_CLOSE, FILL_NEXT_BAR_OPEN);
    }

    public static SpecParameters event(EventSignal event) {
        return new SpecParameters(CURRENT_SCHEMA_VERSION,
                StrategySpecDraft.TEMPLATE_EVENT_SIGNAL, false, null, null,
                Objects.requireNonNull(event, "event"), SIGNAL_EVENT_AVAILABLE,
                FILL_NEXT_BAR_OPEN);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SpecParameters fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("parameters must be an object");
        }
        boolean hasSchema = node.has("schemaVersion");
        boolean hasType = node.has("type");
        if (!hasSchema && !hasType) {
            rejectUnknown(node, LEGACY_SMA_KEYS);
            return new SpecParameters(integer(node, "fastWindow"), integer(node, "slowWindow"),
                    decimal(node, "positionSize"), decimal(node, "stopLossPct"),
                    integer(node, "feeBps"), integer(node, "slippageBps"));
        }
        if (!hasSchema || !hasType || !node.path("schemaVersion").canConvertToInt()
                || node.path("schemaVersion").asInt() != CURRENT_SCHEMA_VERSION
                || !node.path("type").isTextual()) {
            throw new IllegalArgumentException("parameters require schemaVersion=2 and type");
        }
        String type = node.path("type").asText();
        String signalAt = optionalText(node, "signalAt");
        String fillAt = optionalText(node, "fillAt");
        return switch (type) {
            case StrategySpecDraft.TEMPLATE_SMA_CROSS -> {
                rejectUnknown(node, SMA_V2_KEYS);
                requireTiming(signalAt, fillAt, SIGNAL_BAR_CLOSE);
                yield smaV2(integer(node, "fastWindow"), integer(node, "slowWindow"),
                        decimal(node, "positionSize"), decimal(node, "stopLossPct"),
                        integer(node, "feeBps"), integer(node, "slippageBps"));
            }
            case StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT -> {
                rejectUnknown(node, BREAKOUT_KEYS);
                requireTiming(signalAt, fillAt, SIGNAL_BAR_CLOSE);
                yield breakout(integer(node, "lookbackBars"), text(node, "direction"),
                        integer(node, "entryOffsetTicks"), decimal(node, "stopLossPct"),
                        decimal(node, "positionSize"), integer(node, "feeBps"),
                        integer(node, "slippageBps"));
            }
            case StrategySpecDraft.TEMPLATE_EVENT_SIGNAL -> {
                rejectUnknown(node, EVENT_KEYS);
                requireTiming(signalAt, fillAt, SIGNAL_EVENT_AVAILABLE);
                yield event(new EventSignal(text(node, "eventId"), text(node, "sourceId"),
                        text(node, "contentHash"), text(node, "originalPublishedAt"),
                        text(node, "fetchedAt"), text(node, "availableAt"),
                        text(node, "validFrom"), text(node, "validUntil"),
                        text(node, "relatedInstrument"), text(node, "direction"),
                        text(node, "confirmationId"), text(node, "confirmedByMemberId"),
                        text(node, "confirmedAt"), text(node, "sourceStatus"),
                        decimal(node, "stopLossPct"), decimal(node, "positionSize"),
                        integer(node, "feeBps"), integer(node, "slippageBps")));
            }
            default -> throw new IllegalArgumentException("unknown parameter type: " + type);
        };
    }

    private static void requireTiming(String signalAt, String fillAt, String expectedSignal) {
        if (signalAt != null && !expectedSignal.equals(signalAt)) {
            throw new IllegalArgumentException("illegal signalAt");
        }
        if (fillAt != null && !FILL_NEXT_BAR_OPEN.equals(fillAt)) {
            throw new IllegalArgumentException("illegal fillAt");
        }
    }

    @JsonValue
    public Map<String, Object> toJson() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (!legacy) {
            out.put("schemaVersion", schemaVersion);
            out.put("type", type);
        }
        if (sma != null) {
            out.put("fastWindow", sma.fastWindow());
            out.put("slowWindow", sma.slowWindow());
            out.put("positionSize", sma.positionSize());
            out.put("stopLossPct", sma.stopLossPct());
            out.put("feeBps", sma.feeBps());
            out.put("slippageBps", sma.slippageBps());
        } else if (breakout != null) {
            out.put("lookbackBars", breakout.lookbackBars());
            out.put("direction", breakout.direction());
            out.put("entryOffsetTicks", breakout.entryOffsetTicks());
            out.put("stopLossPct", breakout.stopLossPct());
            out.put("positionSize", breakout.positionSize());
            out.put("feeBps", breakout.feeBps());
            out.put("slippageBps", breakout.slippageBps());
        } else if (event != null) {
            out.put("eventId", event.eventId());
            out.put("sourceId", event.sourceId());
            out.put("contentHash", event.contentHash());
            out.put("originalPublishedAt", event.originalPublishedAt());
            out.put("fetchedAt", event.fetchedAt());
            out.put("availableAt", event.availableAt());
            out.put("validFrom", event.validFrom());
            out.put("validUntil", event.validUntil());
            out.put("relatedInstrument", event.relatedInstrument());
            out.put("direction", event.direction());
            out.put("confirmationId", event.confirmationId());
            out.put("confirmedByMemberId", event.confirmedByMemberId());
            out.put("confirmedAt", event.confirmedAt());
            out.put("sourceStatus", event.sourceStatus());
            out.put("stopLossPct", event.stopLossPct());
            out.put("positionSize", event.positionSize());
            out.put("feeBps", event.feeBps());
            out.put("slippageBps", event.slippageBps());
        }
        if (!legacy) {
            out.put("signalAt", signalAt);
            out.put("fillAt", fillAt);
        }
        return out;
    }

    public int schemaVersion() { return schemaVersion; }
    public String type() { return type; }
    public boolean legacy() { return legacy; }
    public String signalAt() { return signalAt; }
    public String fillAt() { return fillAt; }
    public SmaCross sma() { return sma; }
    public PriceBreakout priceBreakout() { return breakout; }
    public EventSignal eventSignal() { return event; }

    public Integer fastWindow() { return sma == null ? null : sma.fastWindow(); }
    public Integer slowWindow() { return sma == null ? null : sma.slowWindow(); }
    public Double positionSize() {
        if (sma != null) return sma.positionSize();
        if (breakout != null) return breakout.positionSize();
        return event == null ? null : event.positionSize();
    }
    public Double stopLossPct() {
        if (sma != null) return sma.stopLossPct();
        if (breakout != null) return breakout.stopLossPct();
        return event == null ? null : event.stopLossPct();
    }
    public Integer feeBps() {
        if (sma != null) return sma.feeBps();
        if (breakout != null) return breakout.feeBps();
        return event == null ? null : event.feeBps();
    }
    public Integer slippageBps() {
        if (sma != null) return sma.slippageBps();
        if (breakout != null) return breakout.slippageBps();
        return event == null ? null : event.slippageBps();
    }

    public boolean isEmpty() {
        return toJson().entrySet().stream()
                .filter(e -> !Set.of("schemaVersion", "type", "signalAt", "fillAt")
                        .contains(e.getKey()))
                .allMatch(e -> e.getValue() == null);
    }

    public Map<String, Object> canonicalMap() {
        return new java.util.TreeMap<>(toJson());
    }

    @Override public boolean equals(Object other) {
        return other instanceof SpecParameters p && canonicalMap().equals(p.canonicalMap());
    }
    @Override public int hashCode() { return canonicalMap().hashCode(); }
    @Override public String toString() { return toJson().toString(); }

    private static void rejectUnknown(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("unknown parameter field: " + name);
            }
        });
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
    private static String optionalText(JsonNode node, String field) {
        return node.has(field) ? text(node, field) : null;
    }
    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue() : null;
    }
    private static Double decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.doubleValue() : null;
    }
    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> out = new java.util.HashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }
}
