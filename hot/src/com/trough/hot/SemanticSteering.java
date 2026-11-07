package com.trough.hot;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Local MIT-licensed POTION-2M inference: WordPiece -> learned vectors -> mean -> L2.
 * No Android, server, generated text, random weights or user-trained parameters.
 * Anchor mixtures are continuous semantic controls, not calibrated probabilities.
 */
public final class SemanticSteering {
    public static final String MODEL_ID = "minishlab/potion-base-2M";
    public static final String REVISION = "389b9f64be5aa4ae7a6bc6fe95ef20ce485ae5da";
    public static final int MAX_INPUT_CHARS = 8192, MAX_TOKENS = 1024;
    public static final String[] AXES = {"calm", "energy", "focus", "warmth", "depth"};
    private static final String[][] ANCHORS = {
        {"Restless tense chaotic turbulent agitation", "Comfortable balanced steady atmosphere", "Calm serene peaceful quiet relaxation"},
        {"Restful still slow gentle subdued motion", "Lively flowing movement at a moderate pace", "Energetic fast dynamic vibrant exciting motion"},
        {"Soft diffuse wandering playful freeform exploration", "Balanced attention and open creative exploration", "Focused precise orderly clear deliberate concentration"},
        {"Cold cool blue cyan icy crisp atmosphere", "Neutral balanced natural light and color", "Warm amber golden orange cozy sunlit atmosphere"},
        {"Flat simple minimal clean surface with little depth", "Layered overlapping forms with moderate perspective", "Deep immersive spacious luminous dimensional layers"}
    };
    private static final String[] SPECIAL = {"[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"};
    private final Map<String,Integer> vocabulary;
    private final ByteBuffer weights;
    private final int rows, dimensions, unknown, maxWordChars;
    private final float[][][] anchors;

    private SemanticSteering(Map<String,Integer> vocabulary, ByteBuffer weights,
            int rows, int dimensions, int unknown, int maxWordChars) {
        this.vocabulary = vocabulary;
        this.weights = weights.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        this.rows = rows;
        this.dimensions = dimensions;
        this.unknown = unknown;
        this.maxWordChars = maxWordChars;
        this.anchors = new float[ANCHORS.length][3][];
        for (int axis = 0; axis < ANCHORS.length; axis++)
            for (int level = 0; level < 3; level++) anchors[axis][level] = encode(ANCHORS[axis][level]);
    }

    /** Load the lossless compact artifact produced by tools/potion-model.py. */
    public static SemanticSteering load(File path) throws IOException {
        if (!path.isFile() || path.length() < 24 || path.length() > 16000000)
            throw new IOException("POTION model size");
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(path.toPath())).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[8]; bytes.get(magic);
        if (!"TRPOTN01".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException("POTION model format");
        int rows = bytes.getInt(), dimensions = bytes.getInt(), unknown = bytes.getInt(), maxWord = bytes.getInt();
        if (rows != 29528 || dimensions != 64 || unknown != 1 || maxWord != 100)
            throw new IOException("POTION model dimensions");
        Map<String,Integer> vocabulary = new HashMap<String,Integer>(rows * 2);
        try {
            for (int row = 0; row < rows; row++) {
                int length = bytes.getInt();
                if (length < 1 || length > 1024 || length > bytes.remaining()) throw new IOException("POTION vocabulary entry");
                byte[] token = new byte[length]; bytes.get(token);
                if (vocabulary.put(new String(token, StandardCharsets.UTF_8), row) != null)
                    throw new IOException("POTION duplicate vocabulary entry");
            }
            if (bytes.remaining() != rows * dimensions * 4) throw new IOException("POTION weight length");
            ByteBuffer data = bytes.slice().order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < rows * dimensions; i++) {
                float value = data.getFloat(i * 4);
                if (Float.isNaN(value) || Float.isInfinite(value)) throw new IOException("POTION non-finite weight");
            }
            return new SemanticSteering(Collections.unmodifiableMap(vocabulary), data, rows, dimensions, unknown, maxWord);
        } catch (java.nio.BufferUnderflowException error) { throw new IOException("POTION truncated model", error); }
    }

    public int dimensions() { return dimensions; }
    public int vocabularySize() { return rows; }

    /** Exposed for the independent Hugging Face tokenizer oracle. Unknown words are omitted. */
    public int[] tokenize(String source) {
        if (source == null) source = "";
        if (source.length() > MAX_INPUT_CHARS) {
            int end = MAX_INPUT_CHARS;
            if (Character.isHighSurrogate(source.charAt(end - 1))) end--;
            source = source.substring(0, end);
        }
        ArrayList<Integer> ids = new ArrayList<Integer>();
        int start = 0, position = 0;
        while (position < source.length() && ids.size() < MAX_TOKENS) {
            String special = null;
            if (source.charAt(position) == '[')
                for (String token : SPECIAL) if (source.startsWith(token, position)) { special = token; break; }
            if (special == null) { position++; continue; }
            basic(source.substring(start, position), ids);
            Integer id = vocabulary.get(special);
            if (id != null && id != unknown && ids.size() < MAX_TOKENS) ids.add(id);
            position += special.length();
            start = position;
        }
        if (ids.size() < MAX_TOKENS) basic(source.substring(start), ids);
        int[] result = new int[Math.min(ids.size(), MAX_TOKENS)];
        for (int i = 0; i < result.length; i++) result[i] = ids.get(i);
        return result;
    }

    private void basic(String source, ArrayList<Integer> ids) {
        StringBuilder clean = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); ) {
            int cp = source.codePointAt(i); i += Character.charCount(cp);
            if (space(cp)) { clean.append(' '); continue; }
            int type = Character.getType(cp);
            if (cp == 0 || cp == 0xFFFD || type == Character.CONTROL || type == Character.FORMAT) continue;
            clean.append(new String(Character.toChars(cp)).toLowerCase(Locale.ROOT));
        }
        String decomposed = Normalizer.normalize(clean, Normalizer.Form.NFD);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < decomposed.length() && ids.size() < MAX_TOKENS; ) {
            int cp = decomposed.codePointAt(i); i += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK) continue;
            if (space(cp) || punctuation(cp) || chinese(cp)) {
                piece(word.toString(), ids); word.setLength(0);
                if (!space(cp) && ids.size() < MAX_TOKENS) piece(new String(Character.toChars(cp)), ids);
            } else word.appendCodePoint(cp);
        }
        if (ids.size() < MAX_TOKENS) piece(word.toString(), ids);
    }

    private void piece(String word, ArrayList<Integer> ids) {
        if (word.length() == 0 || word.codePointCount(0, word.length()) > maxWordChars) return;
        int originalSize = ids.size(), start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer id = null;
            while (end > start) {
                id = vocabulary.get((start == 0 ? "" : "##") + word.substring(start, end));
                if (id != null) break;
                end = word.offsetByCodePoints(end, -1);
            }
            if (id == null || id == unknown) {
                while (ids.size() > originalSize) ids.remove(ids.size() - 1);
                return;
            }
            if (ids.size() < MAX_TOKENS) ids.add(id);
            start = end;
        }
    }

    /** L2-normalized mean of the published learned F32 vectors, with no quantization. */
    public float[] encode(String text) { return pool(tokenize(text)); }

    private float[] pool(int[] ids) {
        float[] result = new float[dimensions];
        if (ids.length == 0) return result;
        for (int id : ids)
            for (int d = 0; d < dimensions; d++) result[d] += weights.getFloat((id * dimensions + d) * 4);
        double norm = 0;
        for (int d = 0; d < dimensions; d++) { result[d] /= ids.length; norm += (double) result[d] * result[d]; }
        norm = Math.sqrt(norm);
        if (norm > 0) for (int d = 0; d < dimensions; d++) result[d] /= norm;
        return result;
    }

    public Result steer(String text) {
        long started = System.nanoTime();
        int[] ids = tokenize(text);
        float[] embedding = pool(ids), values = new float[AXES.length];
        for (int axis = 0; axis < AXES.length; axis++) {
            double[] mixture = new double[3];
            double total = 0, value = 0;
            for (int level = 0; level < 3; level++) {
                double cosine = 0;
                for (int d = 0; d < dimensions; d++) cosine += (double) embedding[d] * anchors[axis][level][d];
                mixture[level] = Math.exp(cosine / 0.20);
                total += mixture[level]; value += mixture[level] * level * 0.5;
            }
            values[axis] = (float) (value / total);
        }
        return new Result(ids.length, System.nanoTime() - started, embedding, values);
    }

    public static final class Result {
        public final int tokenCount;
        public final long inferenceNanos;
        public final float calm, energy, focus, warmth, depth;
        public final float[] embedding;
        public final Map<String,Float> values;
        private Result(int tokens, long elapsed, float[] embedding, float[] controls) {
            tokenCount = tokens; inferenceNanos = elapsed; this.embedding = embedding;
            calm = controls[0]; energy = controls[1]; focus = controls[2]; warmth = controls[3]; depth = controls[4];
            Map<String,Float> map = new LinkedHashMap<String,Float>();
            for (int i = 0; i < AXES.length; i++) map.put(AXES[i], controls[i]);
            values = Collections.unmodifiableMap(map);
        }
        public Map<String,Object> state() {
            Map<String,Object> state = new LinkedHashMap<String,Object>();
            state.put("model", MODEL_ID); state.put("revision", REVISION); state.put("local", true);
            state.put("kind", "learned_semantic_anchor_mixture"); state.put("tokens", tokenCount);
            state.put("inference_ns", inferenceNanos); state.put("values", values);
            return state;
        }
        public Map<String,Object> toMap() { return state(); }
    }

    private static boolean space(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }
    private static boolean punctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) return true;
        int type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
            || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
            || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
            || type == Character.OTHER_PUNCTUATION;
    }
    private static boolean chinese(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
            || (cp >= 0x20000 && cp <= 0x2A6DF) || (cp >= 0x2A700 && cp <= 0x2B73F)
            || (cp >= 0x2B740 && cp <= 0x2B81F) || (cp >= 0x2B820 && cp <= 0x2CEAF)
            || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
