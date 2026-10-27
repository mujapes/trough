package com.trough.hot;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Models Trough runs with its own loop (TroughAgent): home/.trough/models.json,
 *     {"<name>": {"api": "anthropic" | "openai", "base": "<url>", "model": "<id>"}, ...}
 * plain JSON that the user or an agent edits. Keys live apart, one file per model in
 * home/.trough/keys/ (app-private, 0600), set with /key in the chat and never shown again.
 */
final class Models {
    private Models() {}

    static final String MARK = "trough-model";   // Chat's agents map: name -> MARK for these

    static File file(File home) { return new File(home, ".trough/models.json"); }

    static File key(File home, String name) { return new File(home, ".trough/keys/" + name.replaceAll("[^A-Za-z0-9._-]", "_")); }

    /** Seeds models.json on first use; returns the configured names. */
    static List<String> names(File home) {
        List<String> out = new ArrayList<>();
        try {
            File f = file(home);
            if (!f.exists()) {
                f.getParentFile().mkdirs();
                Payloads.writeSmall(f, "{\n"
                    + "  \"deepseek\": {\"api\": \"openai\", \"base\": \"https://api.deepseek.com\", \"model\": \"deepseek-v4-flash\"},\n"
                    + "  \"claude api\": {\"api\": \"anthropic\", \"base\": \"https://api.anthropic.com\", \"model\": \"claude-opus-5-5\"}\n"
                    + "}\n");
            }
            Map<String, Object> m = Json.obj(Json.parse(new String(Payloads.readSmall(f), "UTF-8")));
            if (m != null) out.addAll(m.keySet());
        } catch (Exception ignored) {}
        return out;
    }

    static Map<String, Object> config(File home, String name) throws Exception {
        Map<String, Object> m = Json.obj(Json.parse(new String(Payloads.readSmall(file(home)), "UTF-8")));
        Map<String, Object> c = m == null ? null : Json.obj(m.get(name));
        if (c == null) throw new IllegalArgumentException("no model " + name + " in " + file(home));
        return c;
    }

    /** Null when no key is stored (local servers may need none: then store an empty /key). */
    static String readKey(File home, String name) throws Exception {
        File k = key(home, name);
        return k.exists() ? new String(Payloads.readSmall(k), "UTF-8").trim() : null;
    }

    static void writeKey(File home, String name, String secret) throws Exception {
        File k = key(home, name);
        k.getParentFile().mkdirs();
        Payloads.writeSmall(k, secret);
        k.setReadable(false, false);
        k.setReadable(true, true);
        k.setWritable(false, false);
        k.setWritable(true, true);
    }
}
