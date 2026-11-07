package com.trough.hot;

/** Exact, local letter-value arithmetic for two explicitly named conventions. */
public final class LetterValues {
    private LetterValues() {}

    public static final class Result {
        public final String scheme;
        public final String label;
        public final String normalised;
        public final int total;
        private final char[] letters;
        private final int[] values;

        private Result(String scheme, String label, String normalised, int total,
                       char[] letters, int[] values) {
            this.scheme = scheme;
            this.label = label;
            this.normalised = normalised;
            this.total = total;
            this.letters = letters.clone();
            this.values = values.clone();
        }

        public char[] letters() { return letters.clone(); }
        public int[] values() { return values.clone(); }

        public String equation() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) out.append(" + ");
                out.append(letters[i]).append('(').append(values[i]).append(')');
            }
            return out.append(" = ").append(total).toString();
        }

        public String explain() {
            return "Using " + label + ", “" + normalised + "” has "
                    + letters.length + (letters.length == 1 ? " counted letter" : " counted letters")
                    + " and a total of " + total + ". " + equation()
                    + ".";
        }
    }

    /** Returns null if input is outside the supported conventions or input bounds. */
    public static Result analyse(String source) {
        if (source == null || source.length() > 4096) return null;
        char[] letters = new char[256];
        int[] values = new int[256];
        int count = 0, total = 0, script = 0;
        StringBuilder normalised = new StringBuilder();
        for (int i = 0; i < source.length();) {
            int cp = source.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x064B && cp <= 0x065F) || cp == 0x0670 || cp == 0x0640) continue;
            int value;
            int currentScript;
            char letter;
            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) {
                letter = (char) (cp >= 'a' && cp <= 'z' ? cp - ('a' - 'A') : cp);
                value = letter - 'A' + 1;
                currentScript = 1;
            } else {
                value = arabicValue(cp);
                if (value != 0) {
                    letter = (char) cp;
                    currentScript = 2;
                } else {
                    if (!isIgnored(cp)) return null;
                    continue;
                }
            }
            if ((script != 0 && script != currentScript) || count == 256) return null;
            script = currentScript;
            letters[count] = letter;
            values[count] = value;
            count++;
            total += value;
            normalised.append(letter);
        }
        if (count == 0) return null;
        char[] exactLetters = new char[count];
        int[] exactValues = new int[count];
        System.arraycopy(letters, 0, exactLetters, 0, count);
        System.arraycopy(values, 0, exactValues, 0, count);
        return new Result(script == 1 ? "english-ordinal" : "eastern-arabic-abjad",
                script == 1 ? "English ordinal (A=1…Z=26)" : "Eastern Arabic abjad (base letters)",
                normalised.toString(), total, exactLetters, exactValues);
    }

    private static boolean isIgnored(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isDigit(cp)) return true;
        switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
                return true;
            default:
                return false;
        }
    }

    private static int arabicValue(int cp) {
        switch (cp) {
            case 'ا': return 1;
            case 'ب': return 2;
            case 'ج': return 3;
            case 'د': return 4;
            case 'ه': return 5;
            case 'و': return 6;
            case 'ز': return 7;
            case 'ح': return 8;
            case 'ط': return 9;
            case 'ي': return 10;
            case 'ك': return 20;
            case 'ل': return 30;
            case 'م': return 40;
            case 'ن': return 50;
            case 'س': return 60;
            case 'ع': return 70;
            case 'ف': return 80;
            case 'ص': return 90;
            case 'ق': return 100;
            case 'ر': return 200;
            case 'ش': return 300;
            case 'ت': return 400;
            case 'ث': return 500;
            case 'خ': return 600;
            case 'ذ': return 700;
            case 'ض': return 800;
            case 'ظ': return 900;
            case 'غ': return 1000;
            default: return 0;
        }
    }
}