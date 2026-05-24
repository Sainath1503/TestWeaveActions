package model;

public class ResponseFieldCandidate {

    public final String jsonPath;
    public final String fieldName;
    public final String value;
    public final String previewValue;
    public final String type;

    public ResponseFieldCandidate(String jsonPath, String fieldName, String value, String previewValue, String type) {
        this.jsonPath = jsonPath;
        this.fieldName = fieldName;
        this.value = value;
        this.previewValue = previewValue;
        this.type = type;
    }
}
