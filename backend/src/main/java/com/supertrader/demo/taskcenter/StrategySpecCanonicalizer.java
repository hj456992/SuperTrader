package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Single canonical content/hash implementation shared by freeze and recovery. */
final class StrategySpecCanonicalizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StrategySpecCanonicalizer() {}

    static String hash(String name, String templateType, List<String> instruments,
                       String timeframe, SpecParameters parameters,
                       String entryCondition, String exitCondition,
                       String riskLimits, String assumptions) {
        try {
            TreeMap<String, Object> root = new TreeMap<>();
            root.put("schema", "strategy-spec.v2");
            root.put("name", name);
            root.put("templateType", templateType);
            root.put("instruments", instruments == null ? List.of() : List.copyOf(instruments));
            root.put("timeframe", timeframe);
            root.put("parameters", parameters == null ? Map.of() : parameters.canonicalMap());
            root.put("entryCondition", entryCondition);
            root.put("exitCondition", exitCondition);
            root.put("riskLimits", riskLimits);
            root.put("backtestAssumptions", assumptions);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsBytes(root));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute content hash", e);
        }
    }
}
