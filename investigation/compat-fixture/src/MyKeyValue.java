import io.micrometer.common.KeyValue;

/**
 * A minimal custom KeyValue implementation (no equals/hashCode overrides).
 */
public class MyKeyValue implements KeyValue {

    private final String key;

    private final String value;

    public MyKeyValue(String key, String value) {
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
        return "MyKeyValue(" + key + "=" + value + ")";
    }

}
