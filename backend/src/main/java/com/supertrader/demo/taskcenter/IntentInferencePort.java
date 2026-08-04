package com.supertrader.demo.taskcenter;

import java.util.List;
import java.util.Map;

/**
 * The model intent-inference port (Task 2 / design §6.2).
 *
 * <p>This is the boundary between the deterministic harness and any real model
 * adapter (e.g. the DeepSeek intent adapter). The harness always runs the
 * deterministic {@link IntentClassifier} FIRST and then, optionally, asks the
 * port for a structured model guess. The {@link IntentReconciler} then merges
 * the two — rules take priority over the model.
 *
 * <p>Two factory implementations are used by the Demo:
 * <ul>
 *   <li>{@link #unavailable()} — fail-closed; used when there is no key, in
 *       unit tests and whenever a model call should not run. It returns a
 *       distinct {@link ModelIntent} whose {@code modelUnavailable=true}, so
 *       the harness can report {@code MODEL_UNAVAILABLE} honestly and never
 *       fake a model answer.</li>
 *   <li>the DeepSeek adapter (Task 3) — the only real model implementation.</li>
 * </ul>
 */
public interface IntentInferencePort {

    /** Ask the model for a structured intent guess. Never throws on model
     *  unavailability: a failure-closed {@link ModelIntent} is returned. */
    ModelIntent infer(IntentInferenceRequest request);

    /** Whether a real model is configured (a key + reachable adapter). */
    boolean modelConfigured();

    // ------------------------------------------------------------------ //
    // Fail-closed implementation for offline / no-key / tests.
    // ------------------------------------------------------------------ //

    /** A fail-closed port: no model is ever called. */
    static IntentInferencePort unavailable() {
        return UnavailableIntentInference.INSTANCE;
    }

    /** The request to the model port. {@code allowedLabels} is the exhaustive
     *  label vocabulary the model may emit (unknown labels are dropped). */
    record IntentInferenceRequest(
            String content,
            boolean hasActiveDraft,
            String activeDraftId,
            String activeDraftStatus,
            String pendingField,
            List<String> allowedLabels) {}

    /** The carrier for a model's structured guess. A {@code modelUnavailable}
     *  result carries no guess and no confidence. */
    record ModelIntent(
            IntentResult result,
            boolean modelUnavailable,
            String errorCode) {

        public static ModelIntent unavailable() {
            return new ModelIntent(null, true, "MODEL_UNAVAILABLE");
        }

        public static ModelIntent of(IntentResult result) {
            return new ModelIntent(result, false, null);
        }

        public static ModelIntent failed(String errorCode) {
            return new ModelIntent(null, true, errorCode);
        }
    }

    /** Singleton fail-closed implementation. */
    final class UnavailableIntentInference implements IntentInferencePort {
        static final UnavailableIntentInference INSTANCE = new UnavailableIntentInference();

        @Override
        public ModelIntent infer(IntentInferenceRequest request) {
            return ModelIntent.unavailable();
        }

        @Override
        public boolean modelConfigured() {
            return false;
        }
    }

    /** Convenience for tests: build a model guess with the canonical fields. */
    static IntentResult guess(List<String> labels, String mutation,
                              String authorization, double modelConfidence,
                              Map<String, Object> extractedFields,
                              List<String> ambiguities, boolean requiresConfirmation,
                              String nextQuestion, boolean modelUnavailable) {
        String primary = labels.isEmpty() ? IntentResult.GENERAL_QA : labels.get(0);
        double cal = modelUnavailable ? 0.0 : Math.min(1.0, modelConfidence);
        return new IntentResult(primary, labels, IntentResult.SPEECH_REQUEST,
                IntentResult.DOMAIN_STRATEGY, null, null, mutation, authorization,
                modelUnavailable ? 0.0 : modelConfidence, cal,
                extractedFields, ambiguities, List.of(), requiresConfirmation,
                nextQuestion, modelUnavailable);
    }
}
