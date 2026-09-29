package monoforest.impl.features.documentFeatures;

/** Гласные и специальные символы: один проход по символам каждого поля. */
public class DocumentCharactersFamily extends DocumentFeatureFamily {
    public static final String FIELD_VOWEL_COUNT = "doc_vowel_count";
    public static final String FIELD_SPECIAL_CHARACTER_COUNT = "doc_special_character_count";

    public DocumentCharactersFamily() {
        super(FIELD_VOWEL_COUNT, FIELD_SPECIAL_CHARACTER_COUNT);
    }

    @Override public String getNameFamily() { return "DocumentCharacters"; }
    @Override public String getDescription() { return "Гласные в body и специальные символы в title + body"; }

    @Override
    protected float[] calculateValues(String title, String body) {
        int vowels = 0;
        int special = 0;
        for (int i = 0; i < title.length(); i++) if (isSpecial(title.charAt(i))) special++;
        for (int i = 0; i < body.length(); i++) {
            char character = body.charAt(i);
            if ("aeiouAEIOU".indexOf(character) >= 0) vowels++;
            if (isSpecial(character)) special++;
        }
        return new float[]{vowels, special};
    }

    private static boolean isSpecial(char character) {
        // Сохраняем старое [a-zA-Z0-9\s]: ASCII-пробелы, UTF-16 длина остатка.
        return !(character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9' || character == ' '
                || character >= '\t' && character <= '\r');
    }
}
