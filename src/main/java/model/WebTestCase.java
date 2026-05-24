package model;

import java.util.ArrayList;
import java.util.List;

public class WebTestCase {

    public String testName;
    public String startUrl;
    public List<WebTestStep> steps = new ArrayList<>();
}
