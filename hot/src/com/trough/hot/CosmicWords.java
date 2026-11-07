package com.trough.hot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, offline English ordinal word index for Cosmos. No platform dependencies. */
final class CosmicWords {
    private static final String[][] ORIGINAL_PAIRS = {
        {"MOON", "GLOW"}, {"LISTEN", "SILENT"}, {"EARTH", "HEART"},
        {"NIGHT", "THING"}, {"DUSTY", "STUDY"}, {"ASTRONOMER", "MOON STARER"},
        {"CAT", "ACT"}, {"DOG", "GOD"}
    };

    // An authored pocket vocabulary, rather than an external dictionary or a network service.
    private static final String VOCABULARY =
        "SUN STAR STARS SKY SPACE COSMOS COSMIC ORBIT COMET ASTEROID METEOR NEBULA "
        + "GALAXY PLANET SATURN VENUS MARS MERCURY JUPITER NEPTUNE URANUS PLUTO "
        + "SOLAR LUNAR ECLIPSE EQUINOX SOLSTICE GRAVITY PHOTON QUANTUM AURORA NOVA "
        + "SUPERNOVA STARDUST MOONLIGHT SUNLIGHT SUNRISE SUNSET DAYLIGHT MIDNIGHT "
        + "TWILIGHT DAWN DUSK HORIZON ZENITH INFINITY ETERNITY UNIVERSE PARALLAX "
        + "ROCKET ASTRONAUT TELESCOPE SATELLITE VOYAGER NEBULOUS CELESTIAL RADIANT "
        + "LIGHT DARK SHADOW SPARK FLARE BEAM GLINT SHIMMER GLITTER FLASH SHINE "
        + "FIRE FLAME EMBER ASH SMOKE CLOUD RAIN STORM THUNDER LIGHTNING SNOW ICE "
        + "FROST MIST FOG BREEZE WIND RAINBOW OCEAN SEA WAVE TIDE RIVER STREAM "
        + "LAKE POND WATER ISLAND COAST SHORE BEACH SAND STONE ROCK CRYSTAL GEM "
        + "PEARL GOLD SILVER COPPER IRON AMBER JADE OPAL RUBY DIAMOND SAPPHIRE "
        + "TREE FOREST WOOD LEAF ROOT BRANCH BLOOM FLOWER ROSE LILY LOTUS PETAL "
        + "SEED GARDEN GRASS MOSS FERN IVY CLOVER MEADOW VALLEY MOUNTAIN HILL "
        + "CAVE DESERT DUNE JUNGLE WILLOW CEDAR PINE OAK MAPLE BIRCH SPRING "
        + "SUMMER AUTUMN WINTER SEASON NATURE WILD GREEN BLUE RED ORANGE YELLOW "
        + "PURPLE VIOLET INDIGO PINK WHITE BLACK COLOUR COLOR HUE TONE SHADE "
        + "MUSIC MELODY HARMONY RHYTHM BEAT BASS TREBLE CHORD NOTE SCALE TEMPO "
        + "SONG SING VOICE CHOIR ECHO SOUND SILENCE WHISPER HUM BUZZ DRONE "
        + "GUITAR PIANO DRUM VIOLIN FLUTE HARP SYNTH BANJO CELLO SAXOPHONE "
        + "JAZZ SOUL FUNK BLUES TECHNO HOUSE TRANCE AMBIENT DISCO DANCE GROOVE "
        + "VINYL RECORD ALBUM STEREO RADIO LOOP SAMPLE REMIX REVERB DELAY "
        + "CONCERT STAGE FESTIVAL SPEAKER HEADPHONES WALTZ SWING IMPROVISE "
        + "ART PAINT BRUSH CANVAS INK DRAW SKETCH LINE SHAPE CIRCLE SPIRAL "
        + "CURVE ANGLE PATTERN TEXTURE MOSAIC SCULPTURE POET POEM POETRY "
        + "STORY TALE MYTH LEGEND FABLE NOVEL BOOK PAGE PAPER PEN PENCIL "
        + "WORD LETTER ALPHABET NUMBER MATH MAGIC WONDER MYSTERY RIDDLE "
        + "PUZZLE SECRET CIPHER CODE SIGNAL SYMBOL MIRROR REFLECTION IMAGE "
        + "PHOTO PICTURE CAMERA FILM CINEMA MOVIE FRAME SCENE DREAM VISION "
        + "IDEA THOUGHT MEMORY MIND BRAIN IMAGINE CREATE INVENT DISCOVER "
        + "EXPLORE WANDER ROAM DRIFT FLOAT FLY SOAR LEAP JUMP RUN WALK "
        + "REST SLEEP WAKE BREATHE RELAX CALM PEACE JOY HAPPY LAUGH SMILE "
        + "LOVE KIND HOPE TRUST FRIEND FAMILY HOME HOUSE ROOM DOOR WINDOW "
        + "KEY LOCK TABLE CHAIR SOFA PILLOW BLANKET CANDLE LAMP CLOCK TIME "
        + "MOMENT SECOND MINUTE HOUR TODAY TOMORROW YESTERDAY FUTURE PAST "
        + "PRESENT ALWAYS NEVER AGAIN TOGETHER ALONE HERE THERE NOW NEXT "
        + "COFFEE TEA MILK HONEY BREAD TOAST CAKE COOKIE BISCUIT CHOCOLATE "
        + "APPLE PEAR PEACH PLUM CHERRY BERRY MANGO LEMON LIME BANANA "
        + "MELON COCONUT PINEAPPLE GRAPE OLIVE PIZZA PASTA CHEESE BUTTER "
        + "SUGAR SALT PEPPER SPICE CINNAMON VANILLA GINGER MINT BASIL "
        + "FOX WOLF BEAR LION TIGER OWL EAGLE RAVEN CROW ROBIN SWAN "
        + "WHALE DOLPHIN OTTER SEAL SHARK TURTLE FROG SNAIL BUTTERFLY "
        + "BEE BEETLE MOTH DRAGONFLY FIREFLY RABBIT HARE HORSE ZEBRA "
        + "PANDA KOALA WREN FINCH SQUIRREL OCTOPUS JELLYFISH PENGUIN "
        + "PLAY GAME TOY FUN PARTY ADVENTURE JOURNEY TRAVEL ROAD PATH "
        + "BRIDGE TRAIN BIKE BOAT SHIP SAIL WHEEL FLIGHT SPEED SLOW "
        + "SOFT WARM COOL FRESH SWEET BRIGHT GENTLE QUIET LOUD CURIOUS "
        + "LUCK CHANCE CHOICE CHANGE BALANCE ENERGY MOTION FLOW PULSE "
        + "WISDOM TRUTH BEAUTY FREEDOM COURAGE PATIENCE EMPATHY GRATITUDE";

    private static final List<String> WORDS;
    private static final Map<Integer, List<String>> BY_SUM;
    private static final String[][] SURPRISE_PAIRS;

    static {
        LinkedHashMap<String, String> unique = new LinkedHashMap<String, String>();
        for (String[] pair : ORIGINAL_PAIRS) {
            add(unique, pair[0]);
            add(unique, pair[1]);
        }
        for (String word : VOCABULARY.split(" +")) add(unique, word);
        WORDS = Collections.unmodifiableList(new ArrayList<String>(unique.values()));

        LinkedHashMap<Integer, List<String>> buckets = new LinkedHashMap<Integer, List<String>>();
        for (String word : WORDS) {
            Integer sum = Integer.valueOf(ordinal(word));
            List<String> bucket = buckets.get(sum);
            if (bucket == null) {
                bucket = new ArrayList<String>();
                buckets.put(sum, bucket);
            }
            bucket.add(word);
        }
        for (Map.Entry<Integer, List<String>> entry : buckets.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        BY_SUM = Collections.unmodifiableMap(buckets);
        SURPRISE_PAIRS = buildPairs();
    }

    private CosmicWords() {}

    /** ASCII A..Z only, case insensitive; all other UTF-16 code units contribute zero. */
    static int ordinal(String text) {
        if (text == null) return 0;
        int sum = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') sum += c - 'a' + 1;
            else if (c >= 'A' && c <= 'Z') sum += c - 'A' + 1;
        }
        return sum;
    }

    /** At most twelve distinct indexed words, excluding the input's ASCII letter identity. */
    static List<String> matches(String input) {
        List<String> bucket = BY_SUM.get(Integer.valueOf(ordinal(input)));
        if (bucket == null) return Collections.emptyList();
        String key = letters(input);
        ArrayList<String> result = new ArrayList<String>(Math.min(12, bucket.size()));
        for (String word : bucket) {
            if (!key.equals(letters(word))) result.add(word);
            if (result.size() == 12) break;
        }
        return result;
    }

    static int size() {
        return WORDS.size();
    }

    /** Stable surprise order; callers receive their own arrays, including each pair. */
    static String[][] pairs() {
        String[][] copy = new String[SURPRISE_PAIRS.length][2];
        for (int i = 0; i < copy.length; i++) {
            copy[i][0] = SURPRISE_PAIRS[i][0];
            copy[i][1] = SURPRISE_PAIRS[i][1];
        }
        return copy;
    }

    private static void add(Map<String, String> words, String word) {
        String key = letters(word);
        if (key.length() != 0 && !words.containsKey(key)) words.put(key, word);
    }

    private static String letters(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') out.append((char) (c - 'a' + 'A'));
            else if (c >= 'A' && c <= 'Z') out.append(c);
        }
        return out.toString();
    }

    private static String[][] buildPairs() {
        ArrayList<String[]> pairs = new ArrayList<String[]>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String[] pair : ORIGINAL_PAIRS) appendPair(pairs, seen, pair[0], pair[1]);
        // Walk each value once per round, so the surprises span the vocabulary and number range.
        // Every new pair is a non-anagram: a sum collision can be more surprising than rearrangement.
        for (int round = 0; round < 2 && pairs.size() < 96; round++) {
            for (List<String> bucket : BY_SUM.values()) {
                boolean added = false;
                for (int i = 0; i < bucket.size() && !added; i++) {
                    for (int j = i + 1; j < bucket.size() && !added; j++) {
                        String left = bucket.get(i), right = bucket.get(j);
                        if (!anagram(left, right)) added = appendPair(pairs, seen, left, right);
                    }
                }
                if (pairs.size() == 96) break;
            }
        }
        return pairs.toArray(new String[pairs.size()][2]);
    }

    private static boolean appendPair(List<String[]> out, Set<String> seen,
            String left, String right) {
        String a = letters(left), b = letters(right);
        String key = a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
        if (!seen.add(key)) return false;
        out.add(new String[] {left, right});
        return true;
    }

    private static boolean anagram(String left, String right) {
        char[] a = letters(left).toCharArray(), b = letters(right).toCharArray();
        if (a.length != b.length) return false;
        Arrays.sort(a);
        Arrays.sort(b);
        return Arrays.equals(a, b);
    }
}
