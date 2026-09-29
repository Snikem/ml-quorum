package monoforest.impl.features;

public class FeatureBase {
    String name;
    public float value;

    public FeatureBase(String name, float value) {
        this.name = name;
        this.value = value;
    }

    public String getName() { return name; }
}
