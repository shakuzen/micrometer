import io.micrometer.core.instrument.Tag;

/**
 * A custom Tag implementation with a custom compareTo(Tag): DESCENDING by key.
 * Used to observe which compareTo is dispatched by sorting on old vs new jars.
 */
public class MyTag implements Tag {

    private final String key;

    private final String value;

    public MyTag(String key, String value) {
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
    public int compareTo(Tag o) {
        // intentionally reversed
        return o.getKey().compareTo(getKey());
    }

    @Override
    public String toString() {
        return "MyTag(" + key + "=" + value + ")";
    }

}
