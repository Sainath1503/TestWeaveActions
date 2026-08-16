package model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ApiRequest {

    public String url;
    public String method;
    public Map<String, String> headers;
    public String body;
    public String token;
    public String bodyMode;
    public List<ApiRequestBodyPart> multipartParts = new ArrayList<>();
    public String binaryFilePath;
    public boolean sslVerificationDisabled;
    public String trustStorePath;
    public String trustStorePassword;
    public String keyStorePath;
    public String keyStorePassword;
    public boolean proxyEnabled;
    public String proxyScheme;
    public String proxyHost;
    public int proxyPort;
    public String proxyUsername;
    public String proxyPassword;
}
