package com.trough.hot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A small on-device preference adapter, not a language or image generator.
 * Each axis learns a logistic correction to the semantic/default baseline.
 * Only teach() and explicitly requested visual preferences change its weights;
 * merely observing text, touches or time does not invent a reward or a label.
 * No Android or model-runtime dependency. 256 trainable float parameters.
 */
public final class AdaptiveHeuristic {
    public static final String[] AXES = {
        "calm", "energy", "focus", "warmth", "depth", "text_scale", "contrast", "detail"
    };
    public static final int FEATURE_COUNT = 32;
    public static final int PARAMETER_COUNT = FEATURE_COUNT * 8;
    public static final int FORMAT_VERSION = 1;
    private static final int MAX_TEXT = 2048, TRAIN_STEPS = 16;
    private static final float MAX_WEIGHT = 8f;
    private static final float[] DEFAULTS = {.60f, .42f, .55f, .55f, .60f, .50f, .55f, .60f};
    private static final Pattern NEGATION = Pattern.compile(
            "(^|[^a-z])(no|not|never|dont|don't|cannot|can't|cant|without)([^a-z]|$)");
    private final float[] parameters = new float[PARAMETER_COUNT];
    private final float[] features = new float[FEATURE_COUNT];
    private final float[] baseline = DEFAULTS.clone();
    private long updates, examples, weightsVersion, lastFeedbackMs;
    private String lastFeedback = "", lastSource = "";

    public AdaptiveHeuristic() { features[0] = 1f; }

    /** Bounded input features; ordinal is the current exact letter-value total. */
    public synchronized void observe(String text, Map<String, Object> semanticValues,
                                     int ordinal, boolean busy) {
        Arrays.fill(features, 0f);
        features[0] = 1f;
        for (int axis = 0; axis < AXES.length; axis++) {
            baseline[axis] = DEFAULTS[axis];
            Object raw = semanticValues == null ? null : semanticValues.get(AXES[axis]);
            if (raw instanceof Number) {
                double n = ((Number) raw).doubleValue();
                if (finite(n)) baseline[axis] = bound((float) n, .01f, .99f);
            }
            if (axis < 5) features[axis + 1] = baseline[axis] * 2f - 1f;
        }
        String bounded = bounded(text, MAX_TEXT);
        int n = bounded.length();
        int letters = 0, digits = 0, spaces = 0, punctuation = 0;
        int uppercase = 0, repeats = 0;
        for (int i = 0; i < n; i++) {
            char ch = bounded.charAt(i);
            if (Character.isLetter(ch)) letters++;
            else if (Character.isDigit(ch)) digits++;
            else if (Character.isWhitespace(ch)) spaces++;
            else punctuation++;
            if (Character.isUpperCase(ch)) uppercase++;
            if (i > 0 && ch == bounded.charAt(i - 1)) repeats++;
        }
        float denominator = Math.max(1, n);
        features[6] = n / (float) MAX_TEXT;
        features[7] = letters / denominator;
        features[8] = digits / denominator;
        features[9] = punctuation / denominator;
        features[10] = spaces / denominator;
        features[11] = uppercase / denominator;
        features[12] = repeats / denominator;
        features[13] = (float) (ordinal / (Math.abs((double) ordinal) + 256d));
        features[14] = busy ? 1f : 0f;
        features[15] = n == 0 ? 0f : bounded.indexOf('?') >= 0 ? 1f : -1f;
        // Signed character trigrams preserve a little context without a vocabulary.
        // Each slot is normalized and clipped; arbitrary-length text cannot grow work.
        String lower = bounded.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            int hash = 0x811c9dc5;
            int end = Math.min(lower.length(), i + 3);
            for (int j = i; j < end; j++) hash = (hash ^ lower.charAt(j)) * 0x01000193;
            features[16 + (hash & 15)] += (hash & 16) == 0 ? 1f : -1f;
        }
        float scale = (float) Math.sqrt(Math.max(1, lower.length()));
        for (int i = 16; i < features.length; i++) features[i] = bound(features[i] / scale, -1f, 1f);
    }

    /**
     * Conservative explicit visual-feedback shorthand, including unspaced input.
     * An unsupported or negated request is left for the language model to interpret.
     * Brightness shorthand labels display contrast/intensity, not system brightness.
     */
    public synchronized boolean learnText(String text) {
        String original = bounded(text, MAX_TEXT).toLowerCase(Locale.ROOT);
        String compact = compact(original);
        if (compact.length() == 0 || negated(original, compact)) return false;
        if (startsAny(compact, "what", "why", "how", "does", "did", "example", "quote", "if")
                || (compact.startsWith("would") && !compact.startsWith("wouldyou")))
            return false;
        boolean visual = hasAny(compact, "visual", "animation", "motion", "background", "colour",
                "color", "surface", "scene", "font", "text", "contrast", "detail", "particle", "artwork", "canvas", "display");
        boolean directive = hasAny(compact, "makeit", "makethe", "setthe", "turnthe", "iwant", "iprefer",
                "ineed", "weneed", "please", "canyou", "couldyou", "wouldyou", "increase", "decrease");
        directive = directive || startsAny(compact, "make", "more", "less", "slower", "faster",
                "brighter", "darker", "warmer", "cooler", "bigger", "smaller", "larger", "calmer");
        boolean shortControl = equalsAny(compact, "slower", "faster", "lessmotion", "moremotion",
                "brighter", "darker", "warmer", "cooler", "biggertext", "smallertext",
                "largertext", "lessdetail", "moredetail", "calmer",
                "morecontrast", "lesscontrast", "moredepth", "lessdepth");
        if (!shortControl && !(visual && directive)
                && !hasAny(compact, "makeitslower", "makeitfaster", "makeitbrighter", "makeitdarker",
                        "makeitwarmer", "makeitcooler", "makeitcalmer")) return false;
        boolean learned = false;
        int motion = direction(compact, new String[]{"slower", "lessmotion", "slowdown", "reducemotion"},
                new String[]{"faster", "moremotion", "speedup", "increasemotion"});
        if (motion != 0) { teach("energy", motion < 0 ? .16f : .84f, text); learned = true; }
        int warmth = direction(compact, new String[]{"cooler", "coolercolors", "coolercolours"},
                new String[]{"warmer", "warmercolors", "warmercolours"});
        if (warmth != 0) { teach("warmth", warmth < 0 ? .16f : .84f, text); learned = true; }
        int light = direction(compact, new String[]{"darker", "lesscontrast", "lowercontrast"},
                new String[]{"brighter", "morecontrast", "highercontrast"});
        if (light != 0) { teach("contrast", light < 0 ? .20f : .84f, text); learned = true; }
        int size = direction(compact,
                new String[]{"smallertext", "smallerfont", "textsmaller", "fontsmaller"},
                new String[]{"biggertext", "largertext", "biggerfont", "largerfont", "textbigger",
                        "textlarger", "fontbigger", "fontlarger"});
        if (size != 0) { teach("text_scale", size < 0 ? .20f : .82f, text); learned = true; }
        int detail = direction(compact, new String[]{"lessdetail", "reducedetail", "fewerdetails"},
                new String[]{"moredetail", "increasedetail"});
        if (detail != 0) { teach("detail", detail < 0 ? .18f : .84f, text); learned = true; }
        int depth = direction(compact, new String[]{"lessdepth", "flattervisual"},
                new String[]{"moredepth", "deepervisual"});
        if (depth != 0) { teach("depth", depth < 0 ? .20f : .84f, text); learned = true; }
        if (compact.indexOf("calmer") >= 0) { teach("calm", .84f, text); learned = true; }
        return learned;
    }

    /** A user or an identified cloud teacher supplies a desired normalized output. */
    public synchronized void teach(String axis, float target, String source) {
        int index = axisIndex(axis);
        if (!finite(target)) throw new IllegalArgumentException("Non-finite preference target");
        target = bound(target, .01f, .99f);
        float norm = 0f;
        for (float f : features) norm += f * f;
        norm = Math.max(1f, norm);
        int offset = index * FEATURE_COUNT;
        for (int step = 0; step < TRAIN_STEPS; step++) {
            // Logistic cross-entropy SGD. Normalize for feature length, with a tiny
            // penalty limiting accumulated corrections. No temporary target override.
            float error = predict(index) - target;
            float rate = 1.2f / norm;
            for (int j = 0; j < FEATURE_COUNT; j++) {
                float weight = parameters[offset + j];
                parameters[offset + j] = bound(weight - rate * error * features[j]
                        - .00002f * weight, -MAX_WEIGHT, MAX_WEIGHT);
            }
            updates = increment(updates);
        }
        examples = increment(examples);
        weightsVersion = increment(weightsVersion);
        lastFeedback = AXES[index] + "=" + Float.toString(target);
        lastSource = bounded(source, 256);
        lastFeedbackMs = System.currentTimeMillis();
    }

    public synchronized float value(String axis) { return predict(axisIndex(axis)); }

    public synchronized float[] weights() { return parameters.clone(); }

    public synchronized Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("model", "local-online-preference-adapter");
        state.put("parameters", PARAMETER_COUNT);
        state.put("features", FEATURE_COUNT);
        state.put("updates", updates);
        state.put("examples", examples);
        state.put("weights_version", weightsVersion);
        state.put("last_feedback", lastFeedback);
        state.put("last_source", lastSource);
        state.put("last_feedback_ms", lastFeedbackMs);
        state.put("training", "explicit labels only");
        double norm = 0;
        for (float w : parameters) norm += (double) w * w;
        state.put("weight_norm", Math.sqrt(norm));
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        Map<String, Object> bases = new LinkedHashMap<String, Object>();
        for (int i = 0; i < AXES.length; i++) {
            values.put(AXES[i], predict(i));
            bases.put(AXES[i], baseline[i]);
        }
        state.put("values", values);
        state.put("baseline", bases);
        return state;
    }

    /** JSON retains the 1 KiB learned tensor and small bounded context/diagnostics. */
    public synchronized String encode() {
        Map<String, Object> saved = new LinkedHashMap<String, Object>();
        saved.put("version", FORMAT_VERSION);
        saved.put("feature_count", FEATURE_COUNT);
        saved.put("axes", Arrays.asList(AXES));
        saved.put("weights", numbers(parameters));
        saved.put("context", numbers(features));
        saved.put("baseline", numbers(baseline));
        saved.put("updates", updates);
        saved.put("examples", examples);
        saved.put("weights_version", weightsVersion);
        saved.put("last_feedback", lastFeedback);
        saved.put("last_source", lastSource);
        saved.put("last_feedback_ms", lastFeedbackMs);
        return Json.write(saved);
    }

    /** Validate the entire record before mutating anything; bad data is rejected. */
    public synchronized void restore(String encoded) {
        if (encoded == null || encoded.length() > 32768)
            throw new IllegalArgumentException("Preference record size");
        Map<String, Object> saved = Json.obj(Json.parse(encoded));
        if (saved == null || count(saved, "version") != FORMAT_VERSION
                || count(saved, "feature_count") != FEATURE_COUNT)
            throw new IllegalArgumentException("Preference record version");
        List<Object> axes = Json.arr(saved.get("axes"));
        if (axes == null || axes.size() != AXES.length) throw new IllegalArgumentException("Preference axes");
        for (int i = 0; i < AXES.length; i++)
            if (!AXES[i].equals(axes.get(i))) throw new IllegalArgumentException("Preference axis order");
        float[] nextWeights = vector(saved.get("weights"), PARAMETER_COUNT, -MAX_WEIGHT, MAX_WEIGHT);
        float[] nextContext = vector(saved.get("context"), FEATURE_COUNT, -1f, 1f);
        float[] nextBaseline = vector(saved.get("baseline"), AXES.length, .01f, .99f);
        if (nextContext[0] != 1f) throw new IllegalArgumentException("Preference bias feature");
        long nextUpdates = count(saved, "updates"), nextExamples = count(saved, "examples");
        long nextVersion = count(saved, "weights_version"), nextTime = count(saved, "last_feedback_ms");
        String nextFeedback = text(saved, "last_feedback", 256);
        String nextSource = text(saved, "last_source", 256);
        System.arraycopy(nextWeights, 0, parameters, 0, parameters.length);
        System.arraycopy(nextContext, 0, features, 0, features.length);
        System.arraycopy(nextBaseline, 0, baseline, 0, baseline.length);
        updates = nextUpdates; examples = nextExamples; weightsVersion = nextVersion;
        lastFeedbackMs = nextTime; lastFeedback = nextFeedback; lastSource = nextSource;
    }

    public synchronized void reset() {
        Arrays.fill(parameters, 0f);
        Arrays.fill(features, 0f);
        features[0] = 1f;
        System.arraycopy(DEFAULTS, 0, baseline, 0, baseline.length);
        updates = 0; examples = 0;
        weightsVersion = increment(weightsVersion);
        lastFeedback = "reset"; lastSource = "explicit reset";
        lastFeedbackMs = System.currentTimeMillis();
    }

    private float predict(int axis) {
        double b = baseline[axis];
        double score = Math.log(b / (1d - b));
        int offset = axis * FEATURE_COUNT;
        for (int j = 0; j < FEATURE_COUNT; j++) score += parameters[offset + j] * features[j];
        score = Math.max(-16d, Math.min(16d, score));
        return (float) (1d / (1d + Math.exp(-score)));
    }

    private static int axisIndex(String axis) {
        for (int i = 0; i < AXES.length; i++) if (AXES[i].equals(axis)) return i;
        throw new IllegalArgumentException("Unknown preference axis: " + axis);
    }

    private static float[] vector(Object raw, int size, float low, float high) {
        List<Object> list = Json.arr(raw);
        if (list == null || list.size() != size) throw new IllegalArgumentException("Preference vector size");
        float[] result = new float[size];
        for (int i = 0; i < size; i++) {
            Object value = list.get(i);
            if (!(value instanceof Number)) throw new IllegalArgumentException("Preference vector number");
            double n = ((Number) value).doubleValue();
            if (!finite(n) || n < low || n > high) throw new IllegalArgumentException("Preference vector range");
            result[i] = (float) n;
        }
        return result;
    }

    private static long count(Map<String, Object> saved, String key) {
        Object value = saved.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Preference counter: " + key);
        Number n = (Number) value;
        double d = n.doubleValue();
        long result = n.longValue();
        if (!finite(d) || d < 0 || d != Math.rint(d) || result < 0
                || (value instanceof Double || value instanceof Float) && d >= 0x1.0p63)
            throw new IllegalArgumentException("Preference counter range: " + key);
        return result;
    }

    private static String text(Map<String, Object> saved, String key, int max) {
        Object value = saved.get(key);
        if (!(value instanceof String) || ((String) value).length() > max)
            throw new IllegalArgumentException("Preference text: " + key);
        return (String) value;
    }

    private static List<Object> numbers(float[] vector) {
        List<Object> values = new ArrayList<Object>(vector.length);
        for (float f : vector) values.add(f);
        return values;
    }

    private static boolean negated(String original, String compact) {
        if (NEGATION.matcher(original).find()) return true;
        if (hasAny(compact, "dont", "donot", "never", "without", "ratherthan", "insteadof",
                "notmake", "notwant", "notchange", "notadd", "notmore", "notless")) return true;
        String[] directions = {"slower", "faster", "brighter", "darker", "warmer", "cooler",
                "bigger", "larger", "smaller", "calmer", "moredetail", "lessdetail"};
        for (String direction : directions)
            if (compact.indexOf("not" + direction) >= 0 || compact.indexOf("no" + direction) >= 0) return true;
        return false;
    }

    private static int direction(String text, String[] low, String[] high) {
        int lowAt = -1, highAt = -1;
        for (String token : low) lowAt = Math.max(lowAt, text.lastIndexOf(token));
        for (String token : high) highAt = Math.max(highAt, text.lastIndexOf(token));
        return lowAt < 0 && highAt < 0 ? 0 : lowAt > highAt ? -1 : 1;
    }

    private static boolean hasAny(String text, String... options) {
        for (String s : options) if (text.indexOf(s) >= 0) return true;
        return false;
    }

    private static boolean startsAny(String text, String... options) {
        for (String s : options) if (text.startsWith(s)) return true;
        return false;
    }

    private static boolean equalsAny(String text, String... options) {
        for (String s : options) if (text.equals(s)) return true;
        return false;
    }

    private static String compact(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 'a' && ch <= 'z') out.append(ch);
        }
        return out.toString();
    }

    private static String bounded(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        if (Character.isHighSurrogate(text.charAt(max - 1))) max--;
        return text.substring(0, max);
    }

    private static float bound(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
}
