package model;

public class ApiRequestBodyPart {

    public String name;
    public String value;
    public String filePath;
    public String contentType;
    public boolean file;

    public static ApiRequestBodyPart text(String name, String value) {
        ApiRequestBodyPart part = new ApiRequestBodyPart();
        part.name = name;
        part.value = value;
        part.file = false;
        return part;
    }

    public static ApiRequestBodyPart file(String name, String filePath, String contentType) {
        ApiRequestBodyPart part = new ApiRequestBodyPart();
        part.name = name;
        part.filePath = filePath;
        part.contentType = contentType;
        part.file = true;
        return part;
    }
}
