import io.micrometer.core.instrument.Tag;

/**
 * A minimal Tag implementation without any overrides beyond the abstract methods.
 */
public class PlainTag implements Tag {

    private final String key;

    private final String value;

    public PlainTag(String key, String value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "PlainTag(" + key + "=" + value + ")";
    }

}
