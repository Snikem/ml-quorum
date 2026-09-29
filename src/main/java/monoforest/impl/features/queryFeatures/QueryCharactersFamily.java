package monoforest.impl.features.queryFeatures;

/** Гласные, согласные и специальные символы за один проход по запросу. */
public class QueryCharactersFamily extends QueryFeatureFamily {
    public static final String VOWEL_COUNT = "QueryVowelCount";
    public static final String CONSONANT_COUNT = "QueryConsonantCount";
    public static final String SPECIAL_CHARACTER_COUNT = "QuerySpecialCharacterCount";

    public QueryCharactersFamily() { super(VOWEL_COUNT, CONSONANT_COUNT, SPECIAL_CHARACTER_COUNT); }
    @Override public String getNameFamily() { return "QueryCharacters"; }
    @Override public String getDescription() { return "Количество гласных, согласных и спецсимволов в запросе"; }

    @Override
    protected float[] calculateValues(String query) {
        int vowels = 0, consonants = 0, special = 0;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if ("aeiouAEIOU".indexOf(c) >= 0) vowels++;
            else if ("bcdfghjklmnpqrstvwxyzBCDFGHJKLMNPQRSTVWXYZ".indexOf(c) >= 0) consonants++;
            else if (!(c >= '0' && c <= '9' || c == ' ' || c >= '\t' && c <= '\r')) special++;
        }
        return new float[]{vowels, consonants, special};
    }
}
