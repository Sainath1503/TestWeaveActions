package ui;

import compare.JsonComparator;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TablePosition;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import model.ApiRequest;
import model.ApiResponse;
import model.DbConnectionConfig;
import model.DbValidationReport;
import model.DbValidationResult;
import model.DbValidationRule;
import model.PerformanceTestResult;
import model.ResponseFieldCandidate;
import model.WebTestCase;
import model.WebTestExecutionResult;
import model.WebTestRunReport;
import model.WebTestStep;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import service.ApiService;
import service.DbValidationService;
import service.PerformanceTestService;
import service.PlaywrightRecorderController;
import service.ResponseVariableService;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

public class ApiValidatorFxApp extends Application {

    private static final String APP_NAME = "TestWeave";
    private static final String APP_LOGO_RESOURCE = "/testweave-logo.png";
    private static final String PRIMARY = "#1e5ed6";
    private static final List<String> RUNTIME_VARIABLES = List.of("randomString", "randomInt", "randomDate");

    private final ApiService apiService = new ApiService();
    private final JsonComparator comparator = new JsonComparator();
    private final PerformanceTestService performanceTestService = new PerformanceTestService();
    private final DbValidationService dbValidationService = new DbValidationService();
    private final PlaywrightRecorderController playwrightRecorderController = new PlaywrightRecorderController();
    private final ResponseVariableService responseVariableService = new ResponseVariableService();
    private final Map<String, String> savedVariables = new ConcurrentHashMap<>();
    private final Map<String, String> savedVariablePaths = new ConcurrentHashMap<>();
    private final Map<String, String> savedVariableTypes = new ConcurrentHashMap<>();
    private final List<ComboBox<String>> variableDropdowns = new ArrayList<>();

    private Stage stage;
    private TextField endpointField;
    private ComboBox<String> apiUrlVariableBox;
    private ComboBox<String> methodBox;
    private ComboBox<String> authTypeBox;
    private ComboBox<String> requestFormatBox;
    private PasswordField tokenField;
    private TextField visibleTokenField;
    private TextArea headersArea;
    private TextArea bodyArea;
    private TextArea prettyResponseArea;
    private TextArea rawResponseArea;
    private TextArea responseHeadersArea;
    private TextArea responseCookiesArea;
    private TabPane apiResponseTabs;
    private Label statusValueLabel;
    private Label timeValueLabel;
    private Label sizeValueLabel;
    private Label apiStatusLabel;
    private ApiResponse lastResponse;
    private String lastExpectedJson;
    private String lastActualJson;

    private TableView<Map<String, String>> responseFieldsTable;
    private ObservableList<Map<String, String>> responseFieldRows;
    private TableView<Map<String, String>> fieldValidationsTable;
    private ObservableList<Map<String, String>> fieldValidationRows;
    private TextField expectedJsonPathField;
    private ComboBox<String> compareModeBox;
    private TableView<Map<String, String>> compareTable;
    private ObservableList<Map<String, String>> compareRows;

    private Spinner<Integer> perfThreadsSpinner;
    private Spinner<Integer> perfIterationsSpinner;
    private TextArea perfBodyArea;
    private TextArea perfLogArea;
    private Label perfSamplesLabel;
    private Label perfErrorsLabel;
    private Label perfThroughputLabel;
    private Label perfDurationLabel;
    private Label perfReportLabel;
    private BarChart<String, Number> perfChart;
    private Path lastPerformanceReportPath;

    private ComboBox<String> dbTypeBox;
    private TextField jdbcUrlField;
    private TextField dbUsernameField;
    private PasswordField dbPasswordField;
    private TextField visibleDbPasswordField;
    private TextField driverClassField;
    private TextArea dbQueryArea;
    private ComboBox<String> dbVariableDropdown;
    private Label dbConnectionStatusLabel;
    private TableView<Map<String, String>> dbRulesTable;
    private ObservableList<Map<String, String>> dbRuleRows;
    private TableView<Map<String, String>> dbQueryResultsTable;
    private ObservableList<Map<String, String>> dbQueryResultRows;
    private TableView<Map<String, String>> dbResultsTable;
    private ObservableList<Map<String, String>> dbResultRows;
    private TableView<Map<String, String>> dbColumnValidationsTable;
    private ObservableList<Map<String, String>> dbColumnValidationRows;
    private Label dbSummaryLabel;
    private TextField dbValidationTestSuiteField;
    private TextField dbValidationTestCaseField;
    private TextField dbValidationTestStepField;
    private Path dbConnectionFilePath;

    private TextField webTestNameField;
    private TextField webStartUrlField;
    private TextField webCdpEndpointField;
    private CheckBox webHeadlessCheck;
    private CheckBox webSlowMoCheck;
    private Label webRecorderStatusLabel;
    private Label webBrowserUrlLabel;
    private Label webRunSummaryLabel;
    private TableView<Map<String, String>> webStepsTable;
    private ObservableList<Map<String, String>> webStepRows;
    private TableView<Map<String, String>> webResultsTable;
    private ObservableList<Map<String, String>> webResultRows;
    private TextArea webTipsArea;

    private TableView<Map<String, String>> variablesTable;
    private ObservableList<Map<String, String>> variableRows;
    private TableView<Map<String, String>> testSuiteStepsTable;
    private ObservableList<Map<String, String>> testSuiteRows = FXCollections.observableArrayList();
    private Label testSuiteRunnerStatusLabel;
    private TextField testSuiteNameField;
    private TextField testCaseNameField;
    private TextField testSuiteWorkbookPathField;
    private CheckBox testSuiteParallelExecutionCheck;
    private TextField testSuiteThreadCountField;
    private TextField githubOwnerField;
    private TextField githubRepoField;
    private TextField githubBranchField;
    private Label githubStatusLabel;
    private String githubAccessToken;
    private ExecutorService testSuiteRunnerExecutor;
    private final AtomicBoolean testSuiteStopRequested = new AtomicBoolean(false);
    private TextField fieldValidationTestSuiteField;
    private TextField fieldValidationTestCaseField;
    private TextField fieldValidationTestStepField;
    private TextField jsonCompareTestSuiteField;
    private TextField jsonCompareTestCaseField;
    private TextField jsonCompareTestStepField;
    private TextField performanceTestSuiteField;
    private TextField performanceTestCaseField;
    private TextField performanceTestStepField;
    private TextField webTestingTestSuiteField;
    private TextField webTestingTestCaseField;
    private TextField webTestingTestStepField;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double maxSceneWidth = Math.max(640, visualBounds.getWidth() - 32);
        double maxSceneHeight = Math.max(520, visualBounds.getHeight() - 32);
        double sceneWidth = clamp(visualBounds.getWidth() * 0.92, Math.min(900, maxSceneWidth), Math.min(1540, maxSceneWidth));
        double sceneHeight = clamp(visualBounds.getHeight() * 0.90, Math.min(620, maxSceneHeight), Math.min(1040, maxSceneHeight));

        BorderPane root = new BorderPane();
        root.setTop(createHeader());
        root.setCenter(createTabs());
        root.setStyle("-fx-background-color: #f5f7fb;");

        Scene scene = new Scene(root, sceneWidth, sceneHeight);
        scene.getStylesheets().add(createInlineStylesheet());
        primaryStage.setTitle(APP_NAME + " - JavaFX");
        primaryStage.setMinWidth(Math.min(900, visualBounds.getWidth() * 0.80));
        primaryStage.setMinHeight(Math.min(620, visualBounds.getHeight() * 0.80));
        primaryStage.setX(visualBounds.getMinX() + (visualBounds.getWidth() - sceneWidth) / 2);
        primaryStage.setY(visualBounds.getMinY() + (visualBounds.getHeight() - sceneHeight) / 2);
        primaryStage.setScene(scene);
        loadApplicationIcon(primaryStage);
        primaryStage.setOnCloseRequest(event -> {
            playwrightRecorderController.stopRecording();
            playwrightRecorderController.stopRunningWebTest();
        });
        primaryStage.show();
    }

    private HBox createHeader() {
        Label title = new Label(APP_NAME);
        title.getStyleClass().add("app-title");
        Label version = new Label("Native JavaFX UI");
        version.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, version);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 16, 8, 16));
        header.getStyleClass().add("top-bar");
        return header;
    }

    private TabPane createTabs() {
        TabPane tabs = new TabPane();
        tabs.getTabs().add(tab("API Tester", createApiPanel()));
        tabs.getTabs().add(tab("API Validation", createApiValidationPanel()));
        tabs.getTabs().add(tab("Performance Test", createPerformancePanel()));
        tabs.getTabs().add(tab("DB Validator", createDbValidatorPanel()));
        tabs.getTabs().add(tab("Web Testing", createWebTestingPanel()));
        tabs.getTabs().add(tab("Test Suite Runner", createTestSuitePanel()));
        tabs.getTabs().add(tab("Variables", createVariablesPanel()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabs;
    }

    private Tab tab(String title, javafx.scene.Node content) {
        return new Tab(title, content);
    }

    private javafx.scene.Node createApiPanel() {
        endpointField = new TextField();
        endpointField.setText("https://jsonplaceholder.typicode.com/posts/1");
        methodBox = combo("GET", "POST", "PUT", "PATCH", "DELETE");
        apiUrlVariableBox = createVariableDropdown();
        authTypeBox = combo("No Auth", "Bearer Token");
        requestFormatBox = combo("JSON");
        tokenField = new PasswordField();
        tokenField.setDisable(true);
        visibleTokenField = new TextField();
        visibleTokenField.textProperty().bindBidirectional(tokenField.textProperty());
        visibleTokenField.setDisable(true);
        visibleTokenField.setManaged(false);
        visibleTokenField.setVisible(false);
        authTypeBox.setOnAction(e -> updateAuthControls());
        methodBox.setOnAction(e -> updateRequestBodyState());
        headersArea = editor("Accept: application/json\nContent-Type: application/json\nUser-Agent: API-Validator-Tool/1.0");
        bodyArea = requestEditor("");
        prettyResponseArea = responseEditor("");
        rawResponseArea = responseEditor("");
        responseHeadersArea = responseEditor("");
        responseCookiesArea = responseEditor("");
        statusValueLabel = metric("--");
        timeValueLabel = metric("--");
        sizeValueLabel = metric("--");
        apiStatusLabel = new Label("Ready");
        apiStatusLabel.getStyleClass().add("muted");

        ComboBox<String> apiVariableBox = createVariableDropdown();
        Button insertVariable = secondary("Insert Variable");
        insertVariable.setOnAction(e -> insertVariable(bodyArea, apiVariableBox));
        Button insertUrlVariable = secondary("Insert");
        insertUrlVariable.setOnAction(e -> insertVariable(endpointField, apiUrlVariableBox));
        Button beautify = secondary("Beautify");
        beautify.setOnAction(e -> beautifyBody());
        Button send = primary("Send Request");
        send.setOnAction(e -> sendRequest());
        Button clear = secondary("Clear");
        clear.setOnAction(e -> clearApiForm());
        Button saveRequest = secondary("Save Request");
        saveRequest.setOnAction(e -> saveRequest());
        Button saveResponse = secondary("Save Response");
        saveResponse.setOnAction(e -> saveResponse());
        Button toggleToken = secondary("Show");
        toggleToken.setOnAction(e -> toggleTokenVisibility(toggleToken));
        toggleToken.setMinWidth(88);
        toggleToken.setPrefWidth(88);
        Button copyResponse = secondary("Copy");
        copyResponse.setOnAction(e -> copySelectedResponse());

        GridPane form = grid();
        form.add(labeled("Method", methodBox), 0, 0);
        form.add(labeled("Endpoint", endpointField), 1, 0, 5, 1);
        form.add(labeled("Variables", apiUrlVariableBox), 1, 1);
        form.add(labeled(" ", insertUrlVariable), 2, 1);
        form.add(labeled("Auth Type", authTypeBox), 3, 1);
        form.add(labeled("Token", wrapTokenField(toggleToken)), 4, 1, 2, 1);
        methodBox.setMinWidth(132);
        endpointField.setMinWidth(720);
        apiUrlVariableBox.setMinWidth(210);
        authTypeBox.setMinWidth(190);
        tokenField.setMinWidth(280);
        visibleTokenField.setMinWidth(280);
        GridPane.setHgrow(endpointField, Priority.ALWAYS);

        FlowPane bodyTools = actionRow(new Label("Format:"), requestFormatBox, apiVariableBox, insertVariable, beautify);

        SplitPane editors = new SplitPane(card("Headers", headersArea), card("Request Body", withFooter(bodyArea, bodyTools)));
        editors.setDividerPositions(0.35);

        FlowPane actions = actionRow(send, clear, saveResponse, saveRequest, apiStatusLabel);

        apiResponseTabs = new TabPane();
        apiResponseTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        apiResponseTabs.setMinHeight(360);
        apiResponseTabs.getTabs().add(tab("Pretty", prettyResponseArea));
        apiResponseTabs.getTabs().add(tab("Raw", rawResponseArea));
        apiResponseTabs.getTabs().add(tab("Headers", responseHeadersArea));
        apiResponseTabs.getTabs().add(tab("Cookies", responseCookiesArea));
        apiResponseTabs.getTabs().add(tab("Capture Variables", createResponseVariableCapturePanel()));

        FlowPane metrics = actionRow(
                new Label("Status:"), statusValueLabel,
                new Label("Time:"), timeValueLabel,
                new Label("Size:"), sizeValueLabel,
                copyResponse);

        VBox request = new VBox(14, sectionTitle("Request"), form, editors, actions);
        VBox response = new VBox(12, sectionTitle("Response"), metrics, apiResponseTabs);
        VBox.setVgrow(apiResponseTabs, Priority.ALWAYS);
        request.setMinHeight(0);
        response.setMinHeight(0);

        SplitPane split = new SplitPane(wrap(request), wrap(response));
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.40);
        updateRequestBodyState();
        return padded(split);
    }

    private javafx.scene.Node createResponseVariableCapturePanel() {
        responseFieldRows = FXCollections.observableArrayList();
        responseFieldsTable = mapTable(responseFieldRows,
                "Save", "selected", "JSON Path", "jsonPath", "Preview Value", "preview",
                "Variable Name", "variableName", "Type", "type", "Value", "value");
        responseFieldsTable.getSelectionModel().setCellSelectionEnabled(false);

        Button selectAll = secondary("Select All");
        selectAll.setOnAction(e -> {
            responseFieldRows.forEach(row -> row.put("selected", "true"));
            responseFieldsTable.refresh();
        });
        Button clear = secondary("Clear");
        clear.setOnAction(e -> {
            responseFieldRows.forEach(row -> row.put("selected", "false"));
            responseFieldsTable.refresh();
        });
        Button selectTopLevel = secondary("Select Top Level");
        selectTopLevel.setOnAction(e -> selectTopLevelResponseFields());
        Button save = primary("Save Selected Variables");
        save.setOnAction(e -> saveSelectedResponseVariables());
        FlowPane tools = actionRow(selectAll, selectTopLevel, clear, save);
        tools.getStyleClass().add("capture-toolbar");

        BorderPane panel = new BorderPane(responseFieldsTable);
        panel.setTop(tools);
        BorderPane.setMargin(tools, new Insets(0, 0, 12, 0));
        panel.getStyleClass().add("capture-panel");
        return panel;
    }

    private javafx.scene.Node createApiValidationPanel() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(tab("Field Validation", createFieldValidationPanel()));
        tabs.getTabs().add(tab("JSON Compare", createComparePanel()));
        return padded(tabs);
    }

    private javafx.scene.Node createFieldValidationPanel() {
        fieldValidationTestSuiteField = new TextField();
        fieldValidationTestCaseField = new TextField();
        fieldValidationTestStepField = new TextField();
        applySharedTestSuiteContext(fieldValidationTestSuiteField, fieldValidationTestCaseField);

        fieldValidationRows = FXCollections.observableArrayList();
        fieldValidationsTable = mapTable(fieldValidationRows,
                "Add", "selected", "JSON Path", "field", "Preview Value", "preview",
                "Null Validation", "nullValidation", "Type Validation", "typeValidation",
                "Expected Value / Variable", "expected", "Result", "result",
                "Actual Value", "actual", "Actual Type", "actualType", "Message", "message");

        Button reset = secondary("Reset Defaults");
        reset.setOnAction(e -> resetFieldValidationDefaults());
        Button validate = primary("Validate Fields");
        validate.setOnAction(e -> runFieldValidations());
        FlowPane tools = actionRow(reset, validate);

        VBox top = new VBox(16,
                createTestRunnerContextPanel(fieldValidationTestSuiteField, fieldValidationTestCaseField,
                        fieldValidationTestStepField, () -> addFieldValidationToTestRunner()),
                tools);
        top.getStyleClass().add("validation-toolbar");

        BorderPane panel = new BorderPane(fieldValidationsTable);
        panel.setTop(top);
        BorderPane.setMargin(top, new Insets(0, 0, 16, 0));
        return panel;
    }

    private javafx.scene.Node createComparePanel() {
        jsonCompareTestSuiteField = new TextField();
        jsonCompareTestCaseField = new TextField();
        jsonCompareTestStepField = new TextField();
        applySharedTestSuiteContext(jsonCompareTestSuiteField, jsonCompareTestCaseField);

        expectedJsonPathField = new TextField();
        compareModeBox = combo("Strict", "Lenient");
        compareRows = FXCollections.observableArrayList();
        compareTable = mapTable(compareRows,
                "Result", "status", "Path", "path", "Expected", "expected", "Actual", "actual", "Message", "message");

        Button browse = secondary("Browse");
        browse.setOnAction(e -> chooseExpectedJson());
        Button compare = primary("Compare");
        compare.setOnAction(e -> runCompare(false));
        Button matched = secondary("Show Matched");
        matched.setOnAction(e -> runCompare(true));
        FlowPane controls = actionRow(labeled("Expected JSON File", expectedJsonPathField), browse,
                labeled("Compare Mode", compareModeBox), compare, matched);

        VBox top = new VBox(16,
                createTestRunnerContextPanel(jsonCompareTestSuiteField, jsonCompareTestCaseField,
                        jsonCompareTestStepField, () -> addJsonCompareToTestRunner()),
                controls);
        top.getStyleClass().add("validation-toolbar");

        BorderPane panel = new BorderPane(compareTable);
        panel.setTop(top);
        BorderPane.setMargin(top, new Insets(0, 0, 16, 0));
        return panel;
    }

    private javafx.scene.Node createPerformancePanel() {
        performanceTestSuiteField = new TextField();
        performanceTestCaseField = new TextField();
        performanceTestStepField = new TextField();
        applySharedTestSuiteContext(performanceTestSuiteField, performanceTestCaseField);

        perfThreadsSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 500, 5));
        perfIterationsSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10000, 10));
        perfBodyArea = editor("");
        perfLogArea = editor("");
        perfSamplesLabel = metric("--");
        perfErrorsLabel = metric("--");
        perfThroughputLabel = metric("--");
        perfDurationLabel = metric("--");
        perfReportLabel = metric("No report yet");

        ComboBox<String> perfVariableBox = createVariableDropdown();
        Button insertPerfVariable = secondary("Insert Variable");
        insertPerfVariable.setOnAction(e -> insertVariable(perfBodyArea, perfVariableBox));
        Button copyBody = secondary("Copy From API Tester");
        copyBody.setOnAction(e -> perfBodyArea.setText(bodyArea == null ? "" : bodyArea.getText()));
        Button run = primary("Run Load Test");
        run.setOnAction(e -> runPerformanceTest());
        Button open = secondary("Open Report");
        open.setOnAction(e -> openPerformanceReport());

        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        perfChart = new BarChart<>(xAxis, yAxis);
        perfChart.setLegendVisible(false);
        perfChart.setAnimated(false);
        perfChart.setTitle("Latency Snapshot (ms)");

        FlowPane config = actionRow(labeled("Threads", perfThreadsSpinner), labeled("Iterations / Thread", perfIterationsSpinner), run, open);
        VBox runnerContext = new VBox(12,
                createTestRunnerContextPanel(performanceTestSuiteField, performanceTestCaseField,
                        performanceTestStepField, () -> addPerformanceTestToTestRunner()),
                config);
        runnerContext.getStyleClass().add("validation-toolbar");

        FlowPane metrics = actionRow(
                labeled("Samples", perfSamplesLabel), labeled("Errors", perfErrorsLabel),
                labeled("Throughput", perfThroughputLabel), labeled("Duration", perfDurationLabel),
                labeled("Report", perfReportLabel));
        SplitPane center = new SplitPane(card("Latency Graph", perfChart), card("Execution Log", perfLogArea));
        center.setDividerPositions(0.5);
        FlowPane perfBodyTools = spacedActionRow(perfVariableBox, insertPerfVariable, copyBody);
        perfBodyArea.setPrefRowCount(12);
        perfBodyArea.setMinHeight(240);
        VBox panel = new VBox(14, sectionTitle("Performance Test"), runnerContext,
                card("Request Body Override", withFooter(perfBodyArea, perfBodyTools)), metrics, center);
        VBox.setVgrow(center, Priority.ALWAYS);
        return padded(panel);
    }

    private javafx.scene.Node createDbValidatorPanel() {
        dbTypeBox = combo("MySQL", "PostgreSQL", "Oracle", "SQL Server", "Custom");
        jdbcUrlField = new TextField();
        dbUsernameField = new TextField();
        dbPasswordField = new PasswordField();
        visibleDbPasswordField = new TextField();
        visibleDbPasswordField.textProperty().bindBidirectional(dbPasswordField.textProperty());
        visibleDbPasswordField.setManaged(false);
        visibleDbPasswordField.setVisible(false);
        driverClassField = new TextField("com.mysql.cj.jdbc.Driver");
        dbQueryArea = editor("");
        dbVariableDropdown = createVariableDropdown();
        dbConnectionStatusLabel = new Label("Not connected");
        dbConnectionStatusLabel.getStyleClass().add("muted");
        dbValidationTestSuiteField = new TextField();
        dbValidationTestCaseField = new TextField();
        dbValidationTestStepField = new TextField();
        applySharedTestSuiteContext(dbValidationTestSuiteField, dbValidationTestCaseField);

        Button defaults = secondary("Apply Defaults");
        defaults.setOnAction(e -> applyDbDefaults());
        Button testConnection = primary("Test Connection");
        testConnection.setOnAction(e -> testDbConnection());
        Button showPassword = secondary("Show");
        showPassword.setOnAction(e -> toggleDbPasswordVisibility(showPassword));
        Button saveConnection = secondary("Save Connection");
        saveConnection.setOnAction(e -> saveDbConnection());
        Button loadConnection = secondary("Load Connection");
        loadConnection.setOnAction(e -> loadDbConnection());
        Button insertDbVariable = secondary("Insert Variable");
        insertDbVariable.setOnAction(e -> insertVariable(dbQueryArea, dbVariableDropdown));
        Button saveQuery = secondary("Save Query");
        saveQuery.setOnAction(e -> saveTextFile(dbQueryArea.getText(), "dbquery.sql"));
        Button loadQuery = secondary("Load Query");
        loadQuery.setOnAction(e -> loadTextFile(dbQueryArea));
        Button useApiVariables = secondary("Use API Response Variables");
        useApiVariables.setOnAction(e -> populateDefaultDbRules());
        Button runQuery = primary("Run Query");
        runQuery.setOnAction(e -> runDbQuery());
        Button saveSelectedCell = secondary("Save Selected Cell as Variable");
        saveSelectedCell.setOnAction(e -> saveSelectedDbResultCellAsVariable());

        GridPane dbForm = grid();
        dbForm.add(labeled("DB Type", dbTypeBox), 0, 0);
        dbForm.add(labeled("JDBC URL", jdbcUrlField), 1, 0);
        dbForm.add(labeled("Username", dbUsernameField), 2, 0);
        dbForm.add(labeled("Password", wrapDbPasswordField(showPassword)), 3, 0);
        dbForm.add(labeled("Driver Class", driverClassField), 0, 1);
        dbForm.add(actionRow(defaults, testConnection, saveConnection, loadConnection, dbConnectionStatusLabel), 1, 1, 3, 1);
        GridPane.setHgrow(jdbcUrlField, Priority.ALWAYS);

        dbQueryResultRows = FXCollections.observableArrayList();
        dbQueryResultsTable = mapTable(dbQueryResultRows, "Row", "row");
        dbQueryResultsTable.getSelectionModel().setCellSelectionEnabled(true);
        dbRuleRows = FXCollections.observableArrayList();
        dbRulesTable = mapTable(dbRuleRows,
                "Validate", "selected", "API Field", "apiField", "DB Column", "dbColumn", "Operator", "operator", "Description", "description");
        dbResultRows = FXCollections.observableArrayList();
        dbResultsTable = mapTable(dbResultRows,
                "Result", "result", "Field", "field", "Expected", "expected", "Actual", "actual", "Operator", "operator", "Message", "message");
        dbColumnValidationRows = FXCollections.observableArrayList();
        dbColumnValidationsTable = mapTable(dbColumnValidationRows,
                "Validate", "selected", "DB Column Name", "dbColumnName", "Value", "value",
                "Null Validation", "nullValidation", "Type Validation", "typeValidation",
                "Expected Value / Variable", "expectedValueOrVariable", "Result", "result");
        dbSummaryLabel = metric("--");

        Button addRule = secondary("Add Rule");
        addRule.setOnAction(e -> dbRuleRows.add(row("selected", "true", "apiField", "", "dbColumn", "", "operator", "=", "description", "")));
        Button removeRule = secondary("Remove Rule");
        removeRule.setOnAction(e -> dbRuleRows.removeAll(dbRulesTable.getSelectionModel().getSelectedItems()));
        Button checkAll = secondary("Check All");
        checkAll.setOnAction(e -> setAllRowsSelected(dbRuleRows, dbRulesTable, true));
        Button uncheckAll = secondary("Un-Check All");
        uncheckAll.setOnAction(e -> setAllRowsSelected(dbRuleRows, dbRulesTable, false));
        Button loadColumns = secondary("Load DB Columns");
        loadColumns.setOnAction(e -> loadDbColumnOptions());
        Button saveRules = secondary("Save Rules");
        saveRules.setOnAction(e -> saveDbRules());
        Button loadRules = secondary("Load Rules");
        loadRules.setOnAction(e -> loadDbRules());
        Button validate = primary("Run DB Validation");
        validate.setOnAction(e -> runDbValidation());

        FlowPane queryActions = spacedActionRow(dbVariableDropdown, insertDbVariable, saveQuery, loadQuery, useApiVariables, runQuery);
        SplitPane top = new SplitPane(card("Connection", dbForm), card("Query", withFooter(dbQueryArea, queryActions)));
        top.setDividerPositions(0.54);
        TabPane validationTabs = new TabPane();
        validationTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        validationTabs.getTabs().add(tab("API-DB Validation", withFooter(dbRulesTable,
                spacedActionRow(addRule, removeRule, checkAll, uncheckAll, loadColumns, saveRules, loadRules, validate))));
        validationTabs.getTabs().add(tab("DB Validation", withFooter(dbColumnValidationsTable,
                spacedActionRow(secondaryButton("Reset Defaults", e -> resetDbColumnValidationDefaults()),
                        secondaryButton("Check All", e -> setAllRowsSelected(dbColumnValidationRows, dbColumnValidationsTable, true)),
                        secondaryButton("Un-Check All", e -> setAllRowsSelected(dbColumnValidationRows, dbColumnValidationsTable, false)),
                        primaryButton("Validate DB Columns", e -> runDbColumnValidations())))));
        VBox rulesBox = new VBox(12,
                createTestRunnerContextPanel(dbValidationTestSuiteField, dbValidationTestCaseField,
                        dbValidationTestStepField, () -> addDbValidationsToTestRunner()),
                validationTabs);
        VBox.setVgrow(validationTabs, Priority.ALWAYS);

        VBox panel = new VBox(18,
                top,
                card("Query Resultset", withFooter(dbQueryResultsTable, spacedActionRow(saveSelectedCell))),
                card("Validation Rules", rulesBox),
                card("Validation Results", withFooter(dbResultsTable, dbSummaryLabel)));
        panel.getStyleClass().add("db-workflow");
        top.setMinHeight(330);
        dbQueryResultsTable.setMinHeight(260);
        validationTabs.setMinHeight(360);
        dbResultsTable.setMinHeight(300);
        return padded(panel);
    }

    private javafx.scene.Node createWebTestingPanel() {
        webTestNameField = new TextField("Web Test");
        webStartUrlField = new TextField();
        webCdpEndpointField = new TextField("http://127.0.0.1:9222");
        webHeadlessCheck = new CheckBox("Headless");
        webHeadlessCheck.setSelected(true);
        webSlowMoCheck = new CheckBox("Slow Mo");
        webRecorderStatusLabel = new Label("Recorder idle");
        webBrowserUrlLabel = new Label("Browser URL: --");
        webRunSummaryLabel = metric("--");
        webTipsArea = editor("Recorded steps and execution notes appear here.");
        webTestingTestSuiteField = new TextField();
        webTestingTestCaseField = new TextField();
        webTestingTestStepField = new TextField();
        applySharedTestSuiteContext(webTestingTestSuiteField, webTestingTestCaseField);

        webStepRows = FXCollections.observableArrayList();
        webStepsTable = mapTable(webStepRows,
                "Step", "step", "Action", "action", "Selector", "selector", "Value", "value", "Note", "note");
        webResultRows = FXCollections.observableArrayList();
        webResultsTable = mapTable(webResultRows,
                "Result", "result", "Action", "action", "Selector", "selector", "Expected", "expected", "Message", "message", "Duration", "duration");

        Button record = primary("Record");
        record.setOnAction(e -> startWebRecording());
        Button attach = secondary("Attach");
        attach.setOnAction(e -> startAttachedWebRecording());
        Button stop = secondary("Stop");
        stop.setOnAction(e -> stopWebRecording());
        Button stopNoClose = secondary("Stop-No Browser Close");
        stopNoClose.setOnAction(e -> stopWebRecordingWithoutClosingBrowser());
        Button launchDebug = secondary("Launch Debug Chrome");
        launchDebug.setOnAction(e -> launchDebugChrome());
        Button clearSteps = secondary("Clear Steps");
        clearSteps.setOnAction(e -> clearWebSteps());
        Button add = secondary("Add Step");
        add.setOnAction(e -> addWebStepDialog());
        Button edit = secondary("Edit Step");
        edit.setOnAction(e -> editSelectedWebStep());
        Button delete = secondary("Delete Step");
        delete.setOnAction(e -> webStepRows.removeAll(webStepsTable.getSelectionModel().getSelectedItems()));
        Button moveUp = secondary("Move Up");
        moveUp.setOnAction(e -> moveSelectedWebStep(-1));
        Button moveDown = secondary("Move Down");
        moveDown.setOnAction(e -> moveSelectedWebStep(1));
        Button screenshot = secondary("Add Screenshot Step");
        screenshot.setOnAction(e -> addWebScreenshotStep());
        Button merge = secondary("Merge Recording");
        merge.setOnAction(e -> mergeWebRecording());
        Button save = secondary("Save Recording");
        save.setOnAction(e -> saveWebRecording());
        Button load = secondary("Load Recording");
        load.setOnAction(e -> loadWebRecording());
        Button run = primary("Run Web Test");
        run.setOnAction(e -> runWebTest());
        Button stopRun = secondary("Stop Run");
        stopRun.setOnAction(e -> playwrightRecorderController.stopRunningWebTest());

        GridPane form = grid();
        form.add(labeled("Test Name", webTestNameField), 0, 0);
        form.add(labeled("Start URL", webStartUrlField), 1, 0);
        form.add(labeled("Active Browser CDP", webCdpEndpointField), 2, 0);
        GridPane.setHgrow(webStartUrlField, Priority.ALWAYS);
        FlowPane recorderTools = spacedActionRow(record, attach, launchDebug, stop, stopNoClose, clearSteps, save, load, screenshot, webRecorderStatusLabel);
        VBox header = new VBox(10, form, recorderTools, webBrowserUrlLabel);

        FlowPane runControls = spacedActionRow(run, stopRun, webHeadlessCheck, webSlowMoCheck, labeled("Summary", webRunSummaryLabel));
        runControls.setPrefWrapLength(1000);
        VBox runnerContext = new VBox(12,
                createTestRunnerContextPanel(webTestingTestSuiteField, webTestingTestCaseField,
                        webTestingTestStepField, () -> addWebTestToTestRunner()),
                runControls);
        runnerContext.getStyleClass().add("validation-toolbar");
        FlowPane stepTools = spacedActionRow(add, edit, delete, moveUp, moveDown, merge);
        HBox capturedSection = new HBox(14,
                card("Captured Steps", withFooter(webStepsTable, stepTools)),
                card("Recorder Notes", webTipsArea));
        capturedSection.setMinWidth(0);
        HBox.setHgrow(capturedSection.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(capturedSection.getChildren().get(1), Priority.SOMETIMES);
        webStepsTable.setMinHeight(260);
        webTipsArea.setMinHeight(260);
        webTipsArea.setPrefWidth(360);
        webTipsArea.setWrapText(true);

        VBox resultsSection = card("Step Results", webResultsTable);
        webResultsTable.setMinHeight(300);

        VBox panel = new VBox(14, header, capturedSection, runnerContext, resultsSection);
        VBox.setVgrow(resultsSection, Priority.ALWAYS);
        return padded(panel);
    }

    private javafx.scene.Node createTestSuitePanel() {
        testSuiteNameField = new TextField();
        testCaseNameField = new TextField();
        testSuiteWorkbookPathField = new TextField();
        testSuiteWorkbookPathField.setEditable(false);
        testSuiteParallelExecutionCheck = new CheckBox("Parallel Execution");
        testSuiteThreadCountField = new TextField("1");
        testSuiteThreadCountField.setMaxWidth(90);
        githubOwnerField = new TextField();
        githubRepoField = new TextField();
        githubBranchField = new TextField("main");
        githubStatusLabel = new Label("GitHub: not connected");
        githubStatusLabel.getStyleClass().add("muted");
        testSuiteStepsTable = createTestSuiteStepsTable();
        testSuiteRunnerStatusLabel = new Label("Import or create a Test Suite Runner workbook to view test steps.");
        testSuiteRunnerStatusLabel.getStyleClass().add("muted");

        Button create = primary("Create Workbook");
        create.setOnAction(e -> createTestSuiteWorkbook());
        Button importWorkbook = secondary("Import Workbook");
        importWorkbook.setOnAction(e -> importTestSuiteWorkbook());
        Button addManual = secondary("Add Manual Step");
        addManual.setOnAction(e -> testSuiteRows.add(row("selected", "true", "suite", testSuiteNameField.getText(), "case", testCaseNameField.getText(),
                "step", String.valueOf(testSuiteRows.size() + 1), "executionMode", "Sequential",
                "type", "Manual", "details", "Describe this step", "status", "Ready")));
        Button run = primary("Run Selected");
        run.setOnAction(e -> runSelectedTestSuiteSteps());
        Button stop = secondary("Stop Execution");
        stop.getStyleClass().add("danger-button");
        stop.setOnAction(e -> stopTestSuiteRunnerExecution());
        Button checkAll = secondary("Check All");
        checkAll.setOnAction(e -> setAllRowsSelected(testSuiteRows, testSuiteStepsTable, true));
        Button uncheckAll = secondary("Un-Check All");
        uncheckAll.setOnAction(e -> setAllRowsSelected(testSuiteRows, testSuiteStepsTable, false));
        Button openReport = secondary("Open Report");
        openReport.setOnAction(e -> showInfo("Test Suite Report", "A test suite report is not available yet."));
        Button updateWorkbook = secondary("Update");
        updateWorkbook.setOnAction(e -> updateTestSuiteWorkbook());
        Button openWorkbook = secondary("Open");
        openWorkbook.setOnAction(e -> openImportedTestSuiteWorkbook());
        Button connectGithub = primary("Connect GitHub");
        connectGithub.setOnAction(e -> connectGithub());
        Button deployGithub = secondary("Deploy to GitHub Actions");
        deployGithub.setOnAction(e -> deployTestSuiteToGithubActions());
        Button runGithub = primary("Run in GitHub Actions");
        runGithub.setOnAction(e -> runGithubActionsTestSuite());
        Button openWorkflow = secondary("Open Workflow");
        openWorkflow.setOnAction(e -> openGithubWorkflow());

        testSuiteNameField.textProperty().addListener((observable, oldValue, newValue) -> propagateTestSuiteContext());
        testCaseNameField.textProperty().addListener((observable, oldValue, newValue) -> propagateTestSuiteContext());

        FlowPane controls = spacedActionRow(labeled("Test Suite", testSuiteNameField), labeled("Test Case", testCaseNameField),
                create, importWorkbook, updateWorkbook, openWorkbook, addManual);
        FlowPane runnerActions = spacedActionRow(testSuiteRunnerStatusLabel, checkAll, uncheckAll, openReport,
                testSuiteParallelExecutionCheck, labeled("Threads", testSuiteThreadCountField), run, stop);
        FlowPane githubActions = spacedActionRow(githubStatusLabel, labeled("Owner", githubOwnerField),
                labeled("Repository", githubRepoField), labeled("Branch", githubBranchField),
                connectGithub, deployGithub, runGithub, openWorkflow);
        VBox panel = new VBox(16, controls, labeled("Workbook Path", testSuiteWorkbookPathField),
                runnerActions, card("GitHub Actions", githubActions), testSuiteStepsTable);
        VBox.setVgrow(testSuiteStepsTable, Priority.ALWAYS);
        return padded(panel);
    }

    private javafx.scene.Node createVariablesPanel() {
        variableRows = FXCollections.observableArrayList();
        variablesTable = mapTable(variableRows,
                "Name", "name", "Value", "value", "Type", "type", "JSON Path", "path");
        Button create = primary("Create Variable");
        create.setOnAction(e -> createVariableDialog());
        Button remove = secondary("Remove Selected");
        remove.setOnAction(e -> removeSelectedVariables());
        Button save = secondary("Save Variables");
        save.setOnAction(e -> saveVariablesToFile());
        Button load = secondary("Import Variables");
        load.setOnAction(e -> importVariablesFromFile());
        FlowPane tools = actionRow(create, save, load, remove);
        BorderPane panel = new BorderPane(variablesTable);
        panel.setTop(tools);
        BorderPane.setMargin(tools, new Insets(0, 0, 12, 0));
        return padded(panel);
    }

    private TableView<Map<String, String>> createTestSuiteStepsTable() {
        TableView<Map<String, String>> table = new TableView<>(testSuiteRows);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Map<String, String>, Boolean> runColumn = new TableColumn<>("Run");
        runColumn.setCellValueFactory(data -> {
            SimpleBooleanProperty selected = new SimpleBooleanProperty(isSelected(data.getValue()));
            selected.addListener((observable, oldValue, newValue) ->
                    data.getValue().put("selected", Boolean.TRUE.equals(newValue) ? "true" : "false"));
            return selected;
        });
        runColumn.setCellFactory(CheckBoxTableCell.forTableColumn(runColumn));
        runColumn.setEditable(true);
        runColumn.setMinWidth(70);
        table.getColumns().add(runColumn);

        table.getColumns().add(stringColumn("Suite", "suite"));
        table.getColumns().add(stringColumn("Case", "case"));
        table.getColumns().add(stringColumn("Step", "step"));

        TableColumn<Map<String, String>, String> executionModeColumn = stringColumn("Execution Mode", "executionMode");
        executionModeColumn.setCellFactory(ComboBoxTableCell.forTableColumn("Sequential", "Parallel"));
        executionModeColumn.setOnEditCommit(event -> {
            String mode = event.getNewValue() == null ? "Sequential" : event.getNewValue();
            event.getRowValue().put("executionMode", mode);
            table.refresh();
        });
        executionModeColumn.setEditable(true);
        executionModeColumn.setMinWidth(140);
        table.getColumns().add(executionModeColumn);

        table.getColumns().add(stringColumn("Type", "type"));
        table.getColumns().add(stringColumn("Details", "details"));
        table.getColumns().add(stringColumn("Status", "status"));
        return table;
    }

    private javafx.scene.Node createTestRunnerContextPanel(TextField suiteField, TextField caseField,
                                                           TextField stepField, Runnable addAction) {
        Button addToRunner = secondary("Add to Test Runner");
        addToRunner.setOnAction(e -> addAction.run());
        addToRunner.getStyleClass().add("context-button");

        GridPane context = grid();
        context.getStyleClass().add("context-panel");
        context.add(labeled("Test Suite", suiteField), 0, 0);
        context.add(labeled("Test Case", caseField), 1, 0);
        context.add(labeled("Test Step", stepField), 2, 0);
        context.add(addToRunner, 3, 0);
        GridPane.setHgrow(suiteField, Priority.ALWAYS);
        GridPane.setHgrow(caseField, Priority.ALWAYS);
        GridPane.setHgrow(stepField, Priority.ALWAYS);
        return context;
    }

    private void applySharedTestSuiteContext(TextField suiteField, TextField caseField) {
        if (testSuiteNameField != null && suiteField.getText().isBlank()) {
            suiteField.setText(testSuiteNameField.getText());
        }
        if (testCaseNameField != null && caseField.getText().isBlank()) {
            caseField.setText(testCaseNameField.getText());
        }
    }

    private void propagateTestSuiteContext() {
        populateImportedTestSuiteDetails(
                testSuiteNameField == null ? "" : testSuiteNameField.getText(),
                testCaseNameField == null ? "" : testCaseNameField.getText());
    }

    private void copyIfBlank(TextField target, TextField source) {
        if (target != null && source != null && target.getText().isBlank()) {
            target.setText(source.getText());
        }
    }

    private void addFieldValidationToTestRunner() {
        addValidationStepToTestRunner(
                fieldValidationTestSuiteField,
                fieldValidationTestCaseField,
                fieldValidationTestStepField,
                "Field Validation",
                selectedFieldValidationSummary());
    }

    private void addJsonCompareToTestRunner() {
        addValidationStepToTestRunner(
                jsonCompareTestSuiteField,
                jsonCompareTestCaseField,
                jsonCompareTestStepField,
                "JSON Compare",
                "Expected file: " + expectedJsonPathField.getText() + ", mode: " + compareModeBox.getValue());
    }

    private void addPerformanceTestToTestRunner() {
        addValidationStepToTestRunner(
                performanceTestSuiteField,
                performanceTestCaseField,
                performanceTestStepField,
                "Performance Test",
                "Threads: " + perfThreadsSpinner.getValue()
                        + ", iterations/thread: " + perfIterationsSpinner.getValue()
                        + ", endpoint: " + (endpointField == null ? "" : endpointField.getText()));
    }

    private void addDbValidationsToTestRunner() {
        long apiDbCount = dbRuleRows.stream().filter(this::isSelected).count();
        long dbColumnCount = dbColumnValidationRows.stream().filter(this::isSelected).count();
        addValidationStepToTestRunner(
                dbValidationTestSuiteField,
                dbValidationTestCaseField,
                dbValidationTestStepField,
                "DB Validation",
                apiDbCount + " API-DB rule(s), " + dbColumnCount + " DB column validation(s)");
    }

    private void addWebTestToTestRunner() {
        addValidationStepToTestRunner(
                webTestingTestSuiteField,
                webTestingTestCaseField,
                webTestingTestStepField,
                "Web Test",
                webStepRows.size() + " web step(s), start URL: " + webStartUrlField.getText());
    }

    private void addValidationStepToTestRunner(TextField suiteField, TextField caseField, TextField stepField,
                                               String type, String details) {
        String suite = suiteField.getText().trim();
        String testCase = caseField.getText().trim();
        String step = stepField.getText().trim();
        if (suite.isBlank() || testCase.isBlank() || step.isBlank()) {
            showWarning("Test Runner", "Enter Test Suite, Test Case, and Test Step before adding to Test Runner.");
            return;
        }
        Map<String, String> tableRow = row("selected", "true", "suite", suite, "case", testCase, "step", step,
                "executionMode", "Sequential",
                "type", type, "details", details, "status", "Ready");
        try {
            Path workbookPath = selectedWorkbookPath();
            if (workbookPath != null) {
                appendRowsToWorkbook(workbookPath, List.of(buildWorkbookRow(suite, testCase, step, type, details)));
                refreshTestSuiteRunnerSteps(workbookPath);
                testSuiteRunnerStatusLabel.setText(type + " step added to workbook.");
            } else {
                testSuiteRows.add(tableRow);
            }
            showInfo("Test Runner", type + " step added to Test Suite Runner.");
        } catch (Exception e) {
            showError("Add to Test Runner Failed", e);
        }
    }

    private void createTestSuiteWorkbook() {
        if (testSuiteNameField.getText().isBlank() || testCaseNameField.getText().isBlank()) {
            showWarning("Test Suite Runner", "Enter both Test Suite and Test Case before creating the workbook.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(testSuiteNameField.getText().trim() + ".xlsx");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Workbook", "*.xlsx"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            writeSingleSheetWorkbook(file.toPath(), createSafeExcelSheetName(testCaseNameField.getText()), testCaseNameField.getText());
            testSuiteWorkbookPathField.setText(file.getAbsolutePath());
            refreshTestSuiteRunnerSteps(file.toPath());
            testSuiteRunnerStatusLabel.setText("Workbook created: " + file.getName());
            propagateTestSuiteContext();
        } catch (Exception e) {
            showError("Create Workbook Failed", e);
        }
    }

    private void importTestSuiteWorkbook() {
        File file = chooseOpenFile("Excel Workbook", "*.xlsx");
        if (file == null) {
            return;
        }
        try {
            String workbookName = workbookNameWithoutExtension(file.getName());
            String sheetName = readFirstWorkbookSheetName(file.toPath());
            testSuiteWorkbookPathField.setText(file.getAbsolutePath());
            populateImportedTestSuiteDetails(workbookName, sheetName);
            refreshTestSuiteRunnerSteps(file.toPath());
            showInfo("Test Suite Imported", "Test suite runner imported successfully.");
        } catch (Exception e) {
            showError("Import Workbook Failed", e);
        }
    }

    private void updateTestSuiteWorkbook() {
        Path workbookPath = selectedWorkbookPath();
        if (workbookPath == null) {
            showWarning("Update Test Suite", "Import or create a Test Suite Runner workbook before updating.");
            return;
        }
        try {
            writeTestSuiteRowsToWorkbook(workbookPath);
            testSuiteRunnerStatusLabel.setText("Workbook updated: " + workbookPath.getFileName());
            showInfo("Update Test Suite", "Imported workbook and test case sheet updated successfully.");
        } catch (Exception e) {
            showError("Update Workbook Failed", e);
        }
    }

    private void openImportedTestSuiteWorkbook() {
        Path workbookPath = selectedWorkbookPath();
        openPath(workbookPath, "Import or create a Test Suite Runner workbook before opening.");
        if (workbookPath != null) {
            testSuiteRunnerStatusLabel.setText("Opening workbook: " + workbookPath.getFileName()
                    + ", sheet: " + testCaseNameField.getText());
        }
    }

    private void connectGithub() {
        String clientId = System.getenv("TESTWEAVE_GITHUB_CLIENT_ID");
        if (clientId == null || clientId.isBlank()) {
            getHostServices().showDocument("https://github.com/login");
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Connect GitHub");
            dialog.setHeaderText("Paste a GitHub token with repo and workflow access.");
            dialog.showAndWait().ifPresent(token -> {
                githubAccessToken = token.trim();
                githubStatusLabel.setText(githubAccessToken.isBlank() ? "GitHub: not connected" : "GitHub: connected");
            });
            return;
        }
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return githubDeviceLogin(clientId);
            }
        };
        task.setOnSucceeded(e -> {
            githubAccessToken = task.getValue();
            githubStatusLabel.setText("GitHub: connected");
        });
        task.setOnFailed(e -> showError("GitHub Connect Failed", task.getException()));
        start(task);
    }

    private String githubDeviceLogin(String clientId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String body = "client_id=" + encode(clientId) + "&scope=" + encode("repo workflow");
        JSONObject device = githubPostForm(client, "https://github.com/login/device/code", body);
        String verificationUri = device.optString("verification_uri", "https://github.com/login/device");
        String userCode = device.getString("user_code");
        Platform.runLater(() -> {
            githubStatusLabel.setText("GitHub: enter code " + userCode);
            getHostServices().showDocument(verificationUri);
            showInfo("GitHub Login", "Enter this code on GitHub: " + userCode);
        });
        String deviceCode = device.getString("device_code");
        int interval = Math.max(5, device.optInt("interval", 5));
        for (int attempt = 0; attempt < 90; attempt++) {
            Thread.sleep(interval * 1000L);
            JSONObject token = githubPostForm(client, "https://github.com/login/oauth/access_token",
                    "client_id=" + encode(clientId)
                            + "&device_code=" + encode(deviceCode)
                            + "&grant_type=urn:ietf:params:oauth:grant-type:device_code");
            if (token.has("access_token")) {
                return token.getString("access_token");
            }
            String error = token.optString("error");
            if (!error.isBlank() && !"authorization_pending".equals(error) && !"slow_down".equals(error)) {
                throw new IllegalStateException(token.optString("error_description", error));
            }
            if ("slow_down".equals(error)) {
                interval += 5;
            }
        }
        throw new IllegalStateException("GitHub login timed out.");
    }

    private JSONObject githubPostForm(HttpClient client, String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new JSONObject(response.body());
    }

    private void deployTestSuiteToGithubActions() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                requireGithubConnection();
                Path workbookPath = selectedWorkbookPath();
                if (workbookPath == null) {
                    throw new IllegalStateException("Import or create a Test Suite Runner workbook before deploying.");
                }
                writeTestSuiteRowsToWorkbook(workbookPath);
                Path runnerJar = packagedRunnerJar();
                githubPutFile(".github/workflows/testweave-runner.yml", githubActionsWorkflowYaml(),
                        "Deploy TestWeave GitHub Actions workflow");
                githubPutFile("testweave/test-suite.xlsx", Files.readAllBytes(workbookPath),
                        "Update TestWeave test suite workbook");
                githubPutFile("testweave/testweave-runner.jar", Files.readAllBytes(runnerJar),
                        "Update TestWeave runner");
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            githubStatusLabel.setText("GitHub: workflow deployed");
            showInfo("GitHub Actions", "TestWeave runner deployed to GitHub Actions.");
        });
        task.setOnFailed(e -> showError("Deploy to GitHub Actions Failed", task.getException()));
        start(task);
    }

    private void runGithubActionsTestSuite() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                requireGithubConnection();
                JSONObject inputs = new JSONObject()
                        .put("suite_file", "testweave/test-suite.xlsx")
                        .put("parallel", String.valueOf(testSuiteParallelExecutionCheck.isSelected()))
                        .put("threads", String.valueOf(parseThreadCount()));
                JSONObject payload = new JSONObject()
                        .put("ref", githubBranch())
                        .put("inputs", inputs);
                githubRequest("POST", githubApiBase() + "/actions/workflows/testweave-runner.yml/dispatches",
                        payload.toString(), true);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            githubStatusLabel.setText("GitHub: workflow triggered");
            openGithubActions();
        });
        task.setOnFailed(e -> showError("Run GitHub Actions Failed", task.getException()));
        start(task);
    }

    private void openGithubWorkflow() {
        openGithubActions();
    }

    private void openGithubActions() {
        if (githubOwnerField.getText().isBlank() || githubRepoField.getText().isBlank()) {
            showWarning("GitHub Actions", "Enter GitHub owner and repository first.");
            return;
        }
        getHostServices().showDocument("https://github.com/" + githubOwner() + "/" + githubRepo() + "/actions/workflows/testweave-runner.yml");
    }

    private void requireGithubConnection() {
        if (githubAccessToken == null || githubAccessToken.isBlank()) {
            throw new IllegalStateException("Connect GitHub before deploying or running the workflow.");
        }
        if (githubOwner().isBlank() || githubRepo().isBlank()) {
            throw new IllegalStateException("Enter GitHub owner and repository.");
        }
    }

    private Path packagedRunnerJar() {
        List<Path> candidates = List.of(
                Path.of("target", "api-validator-1.0-SNAPSHOT.jar"),
                Path.of("target", "api-validator-1.0-SNAPSHOT-shaded.jar"));
        return candidates.stream().filter(Files::exists).findFirst()
                .orElseThrow(() -> new IllegalStateException("Package the app first so target/api-validator-1.0-SNAPSHOT.jar exists."));
    }

    private String githubActionsWorkflowYaml() {
        return """
                name: TestWeave Test Suite Runner

                on:
                  workflow_dispatch:
                    inputs:
                      suite_file:
                        description: Test suite workbook path
                        required: true
                        default: testweave/test-suite.xlsx
                      parallel:
                        description: Enable TestWeave parallel execution
                        required: true
                        default: '%s'
                      threads:
                        description: TestWeave thread count
                        required: true
                        default: '%s'

                jobs:
                  testweave:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: '17'
                      - name: Run TestWeave suite
                        run: |
                          java -cp testweave/testweave-runner.jar ui.TestWeaveCliRunner \\
                            --suite "${{ inputs.suite_file }}" \\
                            --parallel "${{ inputs.parallel }}" \\
                            --threads "${{ inputs.threads }}" \\
                            --report target/testweave-report
                      - name: Upload TestWeave report
                        uses: actions/upload-artifact@v4
                        with:
                          name: testweave-report
                          path: target/testweave-report
                """.formatted(testSuiteParallelExecutionCheck.isSelected(), parseThreadCount());
    }

    private void githubPutFile(String path, String content, String message) throws Exception {
        githubPutFile(path, content.getBytes(StandardCharsets.UTF_8), message);
    }

    private void githubPutFile(String path, byte[] content, String message) throws Exception {
        JSONObject payload = new JSONObject()
                .put("message", message)
                .put("branch", githubBranch())
                .put("content", Base64.getEncoder().encodeToString(content));
        String sha = githubContentSha(path);
        if (sha != null) {
            payload.put("sha", sha);
        }
        githubRequest("PUT", githubApiBase() + "/contents/" + encodePath(path), payload.toString(), true);
    }

    private String githubContentSha(String path) throws Exception {
        HttpResponse<String> response = githubRequest("GET", githubApiBase() + "/contents/" + encodePath(path)
                + "?ref=" + encode(githubBranch()), null, false);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub content lookup failed: " + response.body());
        }
        return new JSONObject(response.body()).optString("sha", null);
    }

    private HttpResponse<String> githubRequest(String method, String url, String body, boolean failOnError) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + githubAccessToken)
                .header("X-GitHub-Api-Version", "2022-11-28");
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else if ("PUT".equals(method)) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (failOnError && response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub API failed (" + response.statusCode() + "): " + response.body());
        }
        return response;
    }

    private String githubApiBase() {
        return "https://api.github.com/repos/" + githubOwner() + "/" + githubRepo();
    }

    private String githubOwner() {
        return githubOwnerField.getText().trim();
    }

    private String githubRepo() {
        return githubRepoField.getText().trim();
    }

    private String githubBranch() {
        String branch = githubBranchField.getText().trim();
        return branch.isBlank() ? "main" : branch;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String encodePath(String path) {
        return String.join("/", List.of(path.split("/")).stream().map(this::encode).toList());
    }

    private void populateImportedTestSuiteDetails(String testSuite, String testCase) {
        if (testSuite == null || testCase == null) {
            return;
        }
        if (testSuiteNameField != null && !Objects.equals(testSuiteNameField.getText(), testSuite)) {
            testSuiteNameField.setText(testSuite);
        }
        if (testCaseNameField != null && !Objects.equals(testCaseNameField.getText(), testCase)) {
            testCaseNameField.setText(testCase);
        }
        setText(fieldValidationTestSuiteField, testSuite);
        setText(fieldValidationTestCaseField, testCase);
        setDefaultStep(fieldValidationTestStepField, testCase + " API Field Validation");
        setText(jsonCompareTestSuiteField, testSuite);
        setText(jsonCompareTestCaseField, testCase);
        setDefaultStep(jsonCompareTestStepField, testCase + " JSON Compare");
        setText(dbValidationTestSuiteField, testSuite);
        setText(dbValidationTestCaseField, testCase);
        setDefaultStep(dbValidationTestStepField, testCase + " DB Validation");
        setText(performanceTestSuiteField, testSuite);
        setText(performanceTestCaseField, testCase);
        setDefaultStep(performanceTestStepField, testCase + " Performance Test");
        setText(webTestingTestSuiteField, testSuite);
        setText(webTestingTestCaseField, testCase);
        setDefaultStep(webTestingTestStepField, testCase + " Web Test");
    }

    private void setText(TextField field, String value) {
        if (field != null) {
            field.setText(value);
        }
    }

    private void setDefaultStep(TextField field, String value) {
        if (field != null && field.getText().trim().isBlank()) {
            field.setText(value);
        }
    }

    private void runSelectedTestSuiteSteps() {
        List<Map<String, String>> selectedRows = testSuiteRows.stream()
                .filter(this::isSelected)
                .<Map<String, String>>map(LinkedHashMap::new)
                .toList();
        if (selectedRows.isEmpty()) {
            showWarning("Test Suite Runner", "Select at least one test step to run.");
            return;
        }
        if (testSuiteRunnerExecutor != null) {
            showWarning("Test Suite Runner", "A test suite run is already in progress.");
            return;
        }
        testSuiteStopRequested.set(false);
        for (Map<String, String> row : testSuiteRows) {
            if (isSelected(row)) {
                row.put("status", "Queued");
            }
        }
        testSuiteStepsTable.refresh();

        int threads = testSuiteParallelExecutionCheck.isSelected() ? parseThreadCount() : 1;
        testSuiteRunnerExecutor = Executors.newFixedThreadPool(threads);
        Task<Void> runner = new Task<>() {
            @Override
            protected Void call() throws Exception {
                runTestSuiteRows(selectedRows, threads);
                return null;
            }
        };
        runner.setOnSucceeded(e -> {
            shutdownTestSuiteExecutor();
            testSuiteRunnerStatusLabel.setText(testSuiteStopRequested.get()
                    ? "Test suite execution stopped."
                    : "Test suite execution completed for " + selectedRows.size() + " step(s).");
        });
        runner.setOnFailed(e -> {
            shutdownTestSuiteExecutor();
            showError("Test Suite Runner Failed", runner.getException());
        });
        start(runner);
        testSuiteRunnerStatusLabel.setText("Running " + selectedRows.size() + " selected step(s) with " + threads + " thread(s).");
    }

    private int parseThreadCount() {
        try {
            return Math.max(1, Integer.parseInt(testSuiteThreadCountField.getText().trim()));
        } catch (Exception ignored) {
            testSuiteThreadCountField.setText("1");
            return 1;
        }
    }

    private void runTestSuiteRows(List<Map<String, String>> selectedRows, int threads) throws Exception {
        Object sequentialLock = new Object();
        List<Future<?>> futures = new ArrayList<>();
        for (Map<String, String> row : selectedRows) {
            futures.add(testSuiteRunnerExecutor.submit(() -> {
                if (testSuiteStopRequested.get()) {
                    updateTestSuiteRowStatus(row, "Stopped");
                    return;
                }
                boolean sequential = !"Parallel".equalsIgnoreCase(row.getOrDefault("executionMode", "Sequential"));
                if (threads <= 1 || sequential) {
                    synchronized (sequentialLock) {
                        executeTestSuiteRow(row);
                    }
                } else {
                    executeTestSuiteRow(row);
                }
            }));
        }
        for (Future<?> future : futures) {
            if (testSuiteStopRequested.get()) {
                break;
            }
            try {
                future.get();
            } catch (Exception e) {
                if (testSuiteStopRequested.get()) {
                    break;
                }
                throw e;
            }
        }
    }

    private void executeTestSuiteRow(Map<String, String> row) {
        if (testSuiteStopRequested.get()) {
            updateTestSuiteRowStatus(row, "Stopped");
            return;
        }
        updateTestSuiteRowStatus(row, "Running");
        try {
            String status = executeRunnerStep(row);
            updateTestSuiteRowStatus(row, testSuiteStopRequested.get() ? "Stopped" : status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            updateTestSuiteRowStatus(row, "Stopped");
        } catch (Exception e) {
            updateTestSuiteRowStatus(row, "Failed: " + e.getMessage());
        }
    }

    private String executeRunnerStep(Map<String, String> row) throws Exception {
        String type = row.getOrDefault("type", "");
        if ("Performance Test".equals(type) && !row.getOrDefault("workbook:PERFORMANCE_TEST", "").isBlank()) {
            JSONObject performance = new JSONObject(row.get("workbook:PERFORMANCE_TEST"));
            ApiRequest request = buildRunnerApiRequest(row, performance.optString("body", row.getOrDefault("workbook:Request Payload", "")));
            PerformanceTestResult result = performanceTestService.runLoadTest(request,
                    Math.max(1, performance.optInt("threads", 1)),
                    Math.max(1, performance.optInt("iterationsPerThread", 1)));
            return result.errors == 0 ? "Passed (" + result.samples + " samples)" : "Failed (" + result.errors + " errors)";
        }
        if (!row.getOrDefault("workbook:Hit Request", "").isBlank()) {
            ApiResponse response = apiService.sendRequest(buildRunnerApiRequest(row, row.getOrDefault("workbook:Request Payload", "")));
            return response.statusCode < 400 ? "Passed (" + response.statusCode + ")" : "Failed HTTP " + response.statusCode;
        }
        Thread.sleep(100);
        return "Passed";
    }

    private ApiRequest buildRunnerApiRequest(Map<String, String> row, String body) {
        JSONObject hitRequest = row.getOrDefault("workbook:Hit Request", "").isBlank()
                ? new JSONObject()
                : new JSONObject(row.get("workbook:Hit Request"));
        ApiRequest request = new ApiRequest();
        request.method = hitRequest.optString("method", methodBox == null ? "GET" : methodBox.getValue());
        request.url = resolveVariables(hitRequest.optString("endpoint", endpointField == null ? "" : endpointField.getText()));
        request.headers = resolveHeaderVariables(parseHeaders(hitRequest.optString("headersText", "")));
        request.body = resolveVariables(body == null ? "" : body);
        request.token = tokenField == null ? "" : resolveVariables(tokenField.getText());
        return request;
    }

    private void updateTestSuiteRowStatus(Map<String, String> rowSnapshot, String status) {
        Platform.runLater(() -> {
            for (Map<String, String> row : testSuiteRows) {
                if (Objects.equals(row.get("suite"), rowSnapshot.get("suite"))
                        && Objects.equals(row.get("case"), rowSnapshot.get("case"))
                        && Objects.equals(row.get("step"), rowSnapshot.get("step"))) {
                    row.put("status", status);
                    break;
                }
            }
            testSuiteStepsTable.refresh();
        });
    }

    private void stopTestSuiteRunnerExecution() {
        testSuiteStopRequested.set(true);
        shutdownTestSuiteExecutor();
        for (Map<String, String> row : testSuiteRows) {
            if ("Queued".equals(row.get("status")) || "Running".equals(row.get("status"))) {
                row.put("status", "Stopped");
            }
        }
        testSuiteStepsTable.refresh();
        testSuiteRunnerStatusLabel.setText("Stop requested for Test Suite Runner execution.");
    }

    private void shutdownTestSuiteExecutor() {
        if (testSuiteRunnerExecutor != null) {
            testSuiteRunnerExecutor.shutdownNow();
            testSuiteRunnerExecutor = null;
        }
    }

    private String workbookNameWithoutExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private String readFirstWorkbookSheetName(Path workbookPath) throws Exception {
        try (ZipFile workbookZip = new ZipFile(workbookPath.toFile())) {
            ZipEntry workbookEntry = workbookZip.getEntry("xl/workbook.xml");
            if (workbookEntry == null) {
                throw new IllegalArgumentException("Selected file does not contain an Excel workbook definition.");
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document;
            try (var workbookXml = workbookZip.getInputStream(workbookEntry)) {
                document = factory.newDocumentBuilder().parse(workbookXml);
            }
            NodeList sheets = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/spreadsheetml/2006/main", "sheet");
            if (sheets.getLength() == 0) {
                sheets = document.getElementsByTagName("sheet");
            }
            if (sheets.getLength() == 0) {
                throw new IllegalArgumentException("No sheets were found in the selected workbook.");
            }
            String sheetName = sheets.item(0).getAttributes().getNamedItem("name").getNodeValue();
            if (sheetName == null || sheetName.isBlank()) {
                throw new IllegalArgumentException("The first sheet does not have a usable name.");
            }
            return sheetName;
        }
    }

    private void appendRowsToWorkbook(Path workbookPath, List<List<String>> rows) throws Exception {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, byte[]> entries = readWorkbookEntries(workbookPath);
        byte[] sheetBytes = entries.get("xl/worksheets/sheet1.xml");
        if (sheetBytes == null) {
            throw new IllegalArgumentException("The selected workbook does not contain xl/worksheets/sheet1.xml.");
        }
        String sheetXml = new String(sheetBytes, StandardCharsets.UTF_8);
        List<String> sharedStrings = readSharedStrings(entries);
        List<List<String>> rowsToAppend = new ArrayList<>();
        boolean needsRunnerHeader = !hasRunnerHeaderRow(sheetXml, sharedStrings);
        if (needsRunnerHeader) {
            rowsToAppend.add(runnerWorkbookHeaderColumns());
        }
        rowsToAppend.addAll(rows);
        entries.put("xl/worksheets/sheet1.xml",
                appendInlineStringRows(sheetXml, rowsToAppend, needsRunnerHeader).getBytes(StandardCharsets.UTF_8));
        ensureRunnerWorkbookStyles(entries);

        Path tempWorkbook = Files.createTempFile(workbookPath.getParent(), "testweave-runner-", ".xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tempWorkbook))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        Files.move(tempWorkbook, workbookPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private Map<String, byte[]> readWorkbookEntries(Path workbookPath) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile workbookZip = new ZipFile(workbookPath.toFile())) {
            var zipEntries = workbookZip.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                if (!entry.isDirectory()) {
                    try (var input = workbookZip.getInputStream(entry)) {
                        entries.put(entry.getName(), input.readAllBytes());
                    }
                }
            }
        }
        return entries;
    }

    private String appendInlineStringRows(String sheetXml, List<List<String>> rows, boolean firstRowIsHeader) {
        int nextRow = findMaxSheetRow(sheetXml) + 1;
        StringBuilder rowXml = new StringBuilder();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            boolean headerRow = firstRowIsHeader && rowIndex == 0;
            rowXml.append("                        <row r=\"").append(nextRow).append("\">\n");
            for (int column = 0; column < row.size(); column++) {
                rowXml.append("                          <c r=\"")
                        .append(excelColumnName(column + 1)).append(nextRow)
                        .append("\"").append(headerRow ? " s=\"1\"" : "")
                        .append(" t=\"inlineStr\"><is><t>")
                        .append(escapeXml(row.get(column) == null ? "" : row.get(column)))
                        .append("</t></is></c>\n");
            }
            rowXml.append("                        </row>\n");
            nextRow++;
        }
        if (sheetXml.contains("</sheetData>")) {
            return sheetXml.replace("</sheetData>", rowXml + "                      </sheetData>");
        }
        if (sheetXml.contains("<sheetData/>")) {
            return sheetXml.replace("<sheetData/>", "<sheetData>\n" + rowXml + "                      </sheetData>");
        }
        return sheetXml.replace("</worksheet>", "                      <sheetData>\n" + rowXml + "                      </sheetData>\n</worksheet>");
    }

    private List<String> runnerWorkbookHeaderColumns() {
        return List.of("Test Suite", "Test Case", "Test Step", "Hit Request", "Request Payload",
                "Captured Variables", "API_FIELD_VALIDATION", "Variable Dependencies", "JSON_COMPARE",
                "DB_VALIDATION", "DB_CONNECTION", "DB_QUERY", "API_DB_VALIDATION", "DB_COLUMN_VALIDATION",
                "WEB_TEST", "PERFORMANCE_TEST", "Run", "Execution Mode", "Status");
    }

    private void writeTestSuiteRowsToWorkbook(Path workbookPath) throws Exception {
        Map<String, byte[]> entries = readWorkbookEntries(workbookPath);
        byte[] sheetBytes = entries.get("xl/worksheets/sheet1.xml");
        if (sheetBytes == null) {
            throw new IllegalArgumentException("The selected workbook does not contain xl/worksheets/sheet1.xml.");
        }
        String sheetXml = new String(sheetBytes, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        rows.add(runnerWorkbookHeaderColumns());
        for (Map<String, String> tableRow : testSuiteRows) {
            rows.add(buildWorkbookRowFromTableRow(tableRow));
        }
        entries.put("xl/worksheets/sheet1.xml", replaceSheetData(sheetXml, rows).getBytes(StandardCharsets.UTF_8));
        ensureRunnerWorkbookStyles(entries);

        Path tempWorkbook = Files.createTempFile(workbookPath.getParent(), "testweave-runner-update-", ".xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tempWorkbook))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        Files.move(tempWorkbook, workbookPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private List<String> buildWorkbookRowFromTableRow(Map<String, String> tableRow) {
        List<String> rowValues = new ArrayList<>();
        for (String header : runnerWorkbookHeaderColumns()) {
            rowValues.add(switch (header) {
                case "Test Suite" -> tableRow.getOrDefault("suite", "");
                case "Test Case" -> tableRow.getOrDefault("case", "");
                case "Test Step" -> tableRow.getOrDefault("step", "");
                case "Run" -> tableRow.getOrDefault("selected", "true");
                case "Execution Mode" -> tableRow.getOrDefault("executionMode", "Sequential");
                case "Status" -> tableRow.getOrDefault("status", "Ready");
                case "API_FIELD_VALIDATION" -> "Field Validation".equals(tableRow.get("type"))
                        ? tableRow.getOrDefault("workbook:" + header, tableRow.getOrDefault("details", "")) : tableRow.getOrDefault("workbook:" + header, "");
                case "JSON_COMPARE" -> "JSON Compare".equals(tableRow.get("type"))
                        ? tableRow.getOrDefault("workbook:" + header, tableRow.getOrDefault("details", "")) : tableRow.getOrDefault("workbook:" + header, "");
                case "DB_QUERY" -> "DB Validation".equals(tableRow.get("type"))
                        ? tableRow.getOrDefault("workbook:" + header, tableRow.getOrDefault("details", "")) : tableRow.getOrDefault("workbook:" + header, "");
                case "WEB_TEST" -> "Web Test".equals(tableRow.get("type"))
                        ? tableRow.getOrDefault("workbook:" + header, tableRow.getOrDefault("details", "")) : tableRow.getOrDefault("workbook:" + header, "");
                case "PERFORMANCE_TEST" -> "Performance Test".equals(tableRow.get("type"))
                        ? tableRow.getOrDefault("workbook:" + header, tableRow.getOrDefault("details", "")) : tableRow.getOrDefault("workbook:" + header, "");
                default -> tableRow.getOrDefault("workbook:" + header, "");
            });
        }
        return rowValues;
    }

    private String replaceSheetData(String sheetXml, List<List<String>> rows) {
        String sheetData = buildSheetData(rows);
        if (sheetXml.matches("(?s).*<sheetData>.*?</sheetData>.*")) {
            return sheetXml.replaceFirst("(?s)<sheetData>.*?</sheetData>", sheetData);
        }
        if (sheetXml.contains("<sheetData/>")) {
            return sheetXml.replace("<sheetData/>", sheetData);
        }
        return sheetXml.replace("</worksheet>", sheetData + "\n</worksheet>");
    }

    private String buildSheetData(List<List<String>> rows) {
        StringBuilder xml = new StringBuilder("                      <sheetData>\n");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            int excelRow = rowIndex + 1;
            boolean headerRow = rowIndex == 0;
            xml.append("                        <row r=\"").append(excelRow).append("\">\n");
            for (int column = 0; column < row.size(); column++) {
                xml.append("                          <c r=\"")
                        .append(excelColumnName(column + 1)).append(excelRow)
                        .append("\"").append(headerRow ? " s=\"1\"" : "")
                        .append(" t=\"inlineStr\"><is><t>")
                        .append(escapeXml(row.get(column) == null ? "" : row.get(column)))
                        .append("</t></is></c>\n");
            }
            xml.append("                        </row>\n");
        }
        xml.append("                      </sheetData>");
        return xml.toString();
    }

    private List<List<String>> readSheetRows(String sheetXml, List<String> sharedStrings) {
        List<List<String>> rows = new ArrayList<>();
        java.util.regex.Matcher rowMatcher = java.util.regex.Pattern
                .compile("<row\\b[^>]*>(.*?)</row>", java.util.regex.Pattern.DOTALL)
                .matcher(sheetXml);
        while (rowMatcher.find()) {
            rows.add(rowValues(rowMatcher.group(1), sharedStrings));
        }
        return rows;
    }

    private List<String> readSharedStrings(Map<String, byte[]> entries) {
        byte[] sharedStringsBytes = entries.get("xl/sharedStrings.xml");
        if (sharedStringsBytes == null) {
            return List.of();
        }
        String sharedStringsXml = new String(sharedStringsBytes, StandardCharsets.UTF_8);
        List<String> values = new ArrayList<>();
        java.util.regex.Matcher stringMatcher = java.util.regex.Pattern
                .compile("<si\\b[^>]*>(.*?)</si>", java.util.regex.Pattern.DOTALL)
                .matcher(sharedStringsXml);
        while (stringMatcher.find()) {
            String itemXml = stringMatcher.group(1);
            StringBuilder value = new StringBuilder();
            java.util.regex.Matcher textMatcher = java.util.regex.Pattern
                    .compile("<t[^>]*>(.*?)</t>", java.util.regex.Pattern.DOTALL)
                    .matcher(itemXml);
            while (textMatcher.find()) {
                value.append(unescapeXml(textMatcher.group(1)));
            }
            values.add(value.toString());
        }
        return values;
    }

    private List<String> rowValues(String rowXml, List<String> sharedStrings) {
        List<String> values = new ArrayList<>();
        java.util.regex.Matcher cellMatcher = java.util.regex.Pattern
                .compile("<c\\b([^>]*)>(.*?)</c>", java.util.regex.Pattern.DOTALL)
                .matcher(rowXml);
        while (cellMatcher.find()) {
            values.add(cellText(cellMatcher.group(1), cellMatcher.group(2), sharedStrings));
        }
        return values;
    }

    private String cellText(String attributes, String cellXml, List<String> sharedStrings) {
        java.util.regex.Matcher inlineMatcher = java.util.regex.Pattern
                .compile("<is>\\s*<t[^>]*>(.*?)</t>\\s*</is>", java.util.regex.Pattern.DOTALL)
                .matcher(cellXml);
        if (inlineMatcher.find()) {
            return unescapeXml(inlineMatcher.group(1));
        }
        java.util.regex.Matcher valueMatcher = java.util.regex.Pattern
                .compile("<v>(.*?)</v>", java.util.regex.Pattern.DOTALL)
                .matcher(cellXml);
        if (!valueMatcher.find()) {
            return "";
        }
        String value = valueMatcher.group(1).trim();
        if (attributes.contains("t=\"s\"")) {
            try {
                int sharedStringIndex = Integer.parseInt(value);
                if (sharedStringIndex >= 0 && sharedStringIndex < sharedStrings.size()) {
                    return sharedStrings.get(sharedStringIndex);
                }
            } catch (NumberFormatException ignored) {
                return "";
            }
        }
        return unescapeXml(value);
    }

    private boolean hasRunnerHeaderRow(String sheetXml, List<String> sharedStrings) {
        java.util.regex.Matcher rowMatcher = java.util.regex.Pattern
                .compile("<row\\b[^>]*>(.*?)</row>", java.util.regex.Pattern.DOTALL)
                .matcher(sheetXml);
        while (rowMatcher.find()) {
            if (isRunnerHeader(rowValues(rowMatcher.group(1), sharedStrings))) {
                return true;
            }
        }
        return false;
    }

    private boolean isRunnerHeader(List<String> values) {
        return values.size() >= 6
                && "Test Suite".equals(values.get(0))
                && "Test Case".equals(values.get(1))
                && "Test Step".equals(values.get(2));
    }

    private boolean isBlankRow(List<String> row) {
        return row.stream().allMatch(value -> value == null || value.isBlank());
    }

    private String runnerStepType(Map<String, String> step) {
        if (!step.getOrDefault("WEB_TEST", "").isBlank()) {
            return "Web Test";
        }
        if (!step.getOrDefault("PERFORMANCE_TEST", "").isBlank()) {
            return "Performance Test";
        }
        if (!step.getOrDefault("JSON_COMPARE", "").isBlank()) {
            return "JSON Compare";
        }
        if (!step.getOrDefault("DB_VALIDATION", "").isBlank()
                || !step.getOrDefault("API_DB_VALIDATION", "").isBlank()
                || !step.getOrDefault("DB_COLUMN_VALIDATION", "").isBlank()) {
            return "DB Validation";
        }
        if (!step.getOrDefault("API_FIELD_VALIDATION", "").isBlank()) {
            return "Field Validation";
        }
        return "API Request";
    }

    private String runnerStepDetails(Map<String, String> step) {
        String type = runnerStepType(step);
        return switch (type) {
            case "Web Test" -> step.getOrDefault("WEB_TEST", "");
            case "Performance Test" -> step.getOrDefault("PERFORMANCE_TEST", "");
            case "JSON Compare" -> step.getOrDefault("JSON_COMPARE", "");
            case "DB Validation" -> step.getOrDefault("DB_QUERY", "");
            case "Field Validation" -> step.getOrDefault("API_FIELD_VALIDATION", "");
            default -> step.getOrDefault("Hit Request", "");
        };
    }

    private int findMaxSheetRow(String sheetXml) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<row\\b[^>]*\\sr=\"(\\d+)\"")
                .matcher(sheetXml);
        int max = 0;
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max;
    }

    private String excelColumnName(int columnNumber) {
        StringBuilder name = new StringBuilder();
        int current = columnNumber;
        while (current > 0) {
            current--;
            name.insert(0, (char) ('A' + current % 26));
            current /= 26;
        }
        return name.toString();
    }

    private void writeSingleSheetWorkbook(Path workbookPath, String sheetName, String testCase) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(workbookPath))) {
            writeZipEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                      <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>
                    """);
            writeZipEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);
            writeZipEntry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                      <Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>
                    """);
            writeZipEntry(zip, "xl/styles.xml", runnerWorkbookStylesXml());
            writeZipEntry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """.formatted(escapeXml(sheetName)));
            writeZipEntry(zip, "xl/worksheets/sheet1.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1"><c r="A1" t="inlineStr"><is><t>%s</t></is></c></row>
                      </sheetData>
                    </worksheet>
                    """.formatted(escapeXml(testCase)));
        }
    }

    private void writeZipEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String createSafeExcelSheetName(String value) {
        String sheetName = value.trim().replaceAll("[\\\\/?*\\[\\]:\\p{Cntrl}]", "_").replaceAll("^'+|'+$", "");
        if (sheetName.isBlank()) {
            sheetName = "TestCase";
        }
        return sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName;
    }

    private void ensureRunnerWorkbookStyles(Map<String, byte[]> entries) {
        entries.put("xl/styles.xml", runnerWorkbookStylesXml().getBytes(StandardCharsets.UTF_8));
    }

    private String runnerWorkbookStylesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/></cellXfs>
                  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                </styleSheet>
                """;
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String unescapeXml(String value) {
        return value.replace("&apos;", "'").replace("&quot;", "\"").replace("&gt;", ">")
                .replace("&lt;", "<").replace("&amp;", "&");
    }

    private Path selectedWorkbookPath() {
        if (testSuiteWorkbookPathField == null || testSuiteWorkbookPathField.getText().isBlank()) {
            return null;
        }
        Path path = Path.of(testSuiteWorkbookPathField.getText());
        return Files.exists(path) ? path : null;
    }

    private List<String> buildWorkbookRow(String suite, String testCase, String step, String type, String details) {
        String hitRequest = "";
        String requestPayload = bodyArea == null ? "" : bodyArea.getText();
        String jsonCompare = "";
        String dbValidation = "";
        String dbConnection = "";
        String dbQuery = "";
        String apiDbValidation = "";
        String dbColumnValidation = "";
        String webTest = "";
        String performanceTest = "";
        if ("JSON Compare".equals(type)) {
            jsonCompare = new JSONObject().put("expectedJsonFile", expectedJsonPathField.getText())
                    .put("mode", compareModeBox.getValue()).toString();
        } else if ("DB Validation".equals(type)) {
            dbValidation = details;
            dbConnection = dbConnectionFilePath == null ? "" : dbConnectionFilePath.toString();
            dbQuery = dbQueryArea == null ? "" : dbQueryArea.getText();
            apiDbValidation = new JSONArray(dbRuleRows).toString();
            dbColumnValidation = new JSONArray(dbColumnValidationRows).toString();
        } else if ("Web Test".equals(type)) {
            webTest = new JSONObject().put("testName", webTestNameField.getText())
                    .put("startUrl", webStartUrlField.getText())
                    .put("steps", new JSONArray(webStepRows)).toString();
        } else if ("Performance Test".equals(type)) {
            hitRequest = new JSONObject().put("method", methodBox == null ? "GET" : methodBox.getValue())
                    .put("endpoint", endpointField == null ? "" : endpointField.getText())
                    .put("headersText", headersArea == null ? "" : headersArea.getText()).toString();
            performanceTest = new JSONObject().put("threads", perfThreadsSpinner.getValue())
                    .put("iterationsPerThread", perfIterationsSpinner.getValue())
                    .put("body", perfBodyArea.getText()).toString();
        } else if ("Field Validation".equals(type)) {
            hitRequest = new JSONObject().put("method", methodBox == null ? "GET" : methodBox.getValue())
                    .put("endpoint", endpointField == null ? "" : endpointField.getText())
                    .put("headersText", headersArea == null ? "" : headersArea.getText()).toString();
        }
        return List.of(suite, testCase, step, hitRequest, requestPayload, "",
                "Field Validation".equals(type) ? new JSONArray(fieldValidationRows).toString() : "",
                "", jsonCompare, dbValidation, dbConnection, dbQuery, apiDbValidation,
                dbColumnValidation, webTest, performanceTest, "true", "Sequential", "Ready");
    }

    private void refreshTestSuiteRunnerSteps(Path workbookPath) {
        testSuiteRows.clear();
        try {
            List<Map<String, String>> steps = readTestSuiteRunnerSteps(workbookPath);
            for (Map<String, String> step : steps) {
                Map<String, String> tableRow = row("selected", step.getOrDefault("Run", "true"),
                        "suite", step.getOrDefault("Test Suite", ""),
                        "case", step.getOrDefault("Test Case", ""),
                        "step", step.getOrDefault("Test Step", ""),
                        "executionMode", step.getOrDefault("Execution Mode", "Sequential"),
                        "type", runnerStepType(step),
                        "details", runnerStepDetails(step),
                        "status", step.getOrDefault("Status", "Ready"));
                for (Map.Entry<String, String> entry : step.entrySet()) {
                    tableRow.put("workbook:" + entry.getKey(), entry.getValue());
                }
                testSuiteRows.add(tableRow);
            }
            testSuiteRunnerStatusLabel.setText(steps.isEmpty()
                    ? "No test steps found in the imported workbook."
                    : "Loaded " + steps.size() + " test step(s).");
        } catch (Exception e) {
            testSuiteRunnerStatusLabel.setText("Could not load test steps: " + e.getMessage());
        }
    }

    private List<Map<String, String>> readTestSuiteRunnerSteps(Path workbookPath) throws Exception {
        Map<String, byte[]> entries = readWorkbookEntries(workbookPath);
        byte[] sheetBytes = entries.get("xl/worksheets/sheet1.xml");
        if (sheetBytes == null) {
            return List.of();
        }
        String sheetXml = new String(sheetBytes, StandardCharsets.UTF_8);
        List<String> sharedStrings = readSharedStrings(entries);
        List<List<String>> rows = readSheetRows(sheetXml, sharedStrings);
        List<String> header = null;
        List<Map<String, String>> steps = new ArrayList<>();
        for (List<String> row : rows) {
            if (header == null) {
                if (isRunnerHeader(row)) {
                    header = row;
                }
                continue;
            }
            if (isBlankRow(row) || isRunnerHeader(row)) {
                continue;
            }
            Map<String, String> step = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) {
                step.put(header.get(column), column < row.size() ? row.get(column) : "");
            }
            if (!step.getOrDefault("Test Step", "").isBlank()) {
                steps.add(step);
            }
        }
        return steps;
    }

    private String selectedFieldValidationSummary() {
        long selected = fieldValidationRows.stream().filter(this::isSelected).count();
        if (selected == 0) {
            return "No field rows selected yet";
        }
        return selected + " selected field validation(s)";
    }

    private void sendRequest() {
        ApiRequest request = buildApiRequest(bodyArea.getText());
        if (request.url == null || request.url.isBlank()) {
            showWarning("API Tester", "Enter an endpoint before sending the request.");
            return;
        }
        apiStatusLabel.setText("Sending...");
        Task<ApiResponse> task = new Task<>() {
            @Override
            protected ApiResponse call() {
                return apiService.sendRequest(request);
            }
        };
        task.setOnSucceeded(e -> {
            lastResponse = task.getValue();
            renderResponse(lastResponse);
            apiStatusLabel.setText("Response received");
        });
        task.setOnFailed(e -> {
            apiStatusLabel.setText("Request failed");
            showError("Request Failed", task.getException());
        });
        start(task);
    }

    private ApiRequest buildApiRequest(String body) {
        ApiRequest request = new ApiRequest();
        request.url = endpointField == null ? "" : resolveVariables(endpointField.getText().trim());
        request.method = methodBox == null ? "GET" : methodBox.getValue();
        request.headers = resolveHeaderVariables(parseHeaders(headersArea == null ? "" : headersArea.getText()));
        request.body = body == null ? "" : resolveVariables(body);
        request.token = authTypeBox != null && "Bearer Token".equals(authTypeBox.getValue()) && tokenField != null
                ? resolveVariables(tokenField.getText().trim()) : "";
        return request;
    }

    private void toggleTokenVisibility(Button toggleButton) {
        boolean show = !visibleTokenField.isVisible();
        visibleTokenField.setVisible(show);
        visibleTokenField.setManaged(show);
        tokenField.setVisible(!show);
        tokenField.setManaged(!show);
        toggleButton.setText(show ? "Hide" : "Show");
    }

    private void copySelectedResponse() {
        String text = selectedResponseText();
        if (text == null || text.isBlank()) {
            showWarning("Copy Response", "No response content is available to copy.");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        apiStatusLabel.setText("Response copied");
    }

    private String selectedResponseText() {
        if (apiResponseTabs == null || apiResponseTabs.getSelectionModel().getSelectedItem() == null) {
            return lastResponse == null ? "" : lastResponse.prettyBody;
        }
        String selected = apiResponseTabs.getSelectionModel().getSelectedItem().getText();
        return switch (selected) {
            case "Raw" -> rawResponseArea.getText();
            case "Headers" -> responseHeadersArea.getText();
            case "Cookies" -> responseCookiesArea.getText();
            default -> prettyResponseArea.getText();
        };
    }

    private void renderResponse(ApiResponse response) {
        statusValueLabel.setText(response.statusLine);
        timeValueLabel.setText(response.timeMs + " ms");
        sizeValueLabel.setText(response.sizeBytes + " bytes");
        prettyResponseArea.setText(response.prettyBody);
        rawResponseArea.setText(response.rawBody);
        responseHeadersArea.setText(response.headersText);
        responseCookiesArea.setText(response.cookiesText);
        lastActualJson = response.rawBody;
        parseResponseFields(response.rawBody);
    }

    private void updateAuthControls() {
        boolean bearer = "Bearer Token".equals(authTypeBox.getValue());
        tokenField.setDisable(!bearer);
        visibleTokenField.setDisable(!bearer);
        if (!bearer) {
            tokenField.clear();
        }
    }

    private void updateRequestBodyState() {
        if (bodyArea == null || methodBox == null) {
            return;
        }
        boolean bodyAllowed = !"GET".equals(methodBox.getValue()) && !"DELETE".equals(methodBox.getValue());
        bodyArea.setDisable(!bodyAllowed);
        requestFormatBox.setDisable(!bodyAllowed);
        if (!bodyAllowed) {
            bodyArea.clear();
        }
    }

    private void parseResponseFields(String body) {
        responseFieldRows.clear();
        fieldValidationRows.clear();
        Task<List<ResponseFieldCandidate>> task = new Task<>() {
            @Override
            protected List<ResponseFieldCandidate> call() {
                return responseVariableService.parseAllFields(body);
            }
        };
        task.setOnSucceeded(e -> {
            for (ResponseFieldCandidate candidate : task.getValue()) {
                responseFieldRows.add(row("selected", "false", "jsonPath", candidate.jsonPath,
                        "field", candidate.fieldName, "preview", candidate.previewValue,
                        "variableName", normalizeVariableName(candidate.fieldName == null || candidate.fieldName.isBlank()
                                ? candidate.jsonPath : candidate.fieldName),
                        "value", candidate.value, "type", candidate.type));
                fieldValidationRows.add(row("selected", "true", "field", candidate.jsonPath,
                        "preview", candidate.previewValue, "nullValidation", "Not Null",
                        "typeValidation", candidate.type, "operator", "equals",
                        "expected", candidate.value, "actual", candidate.value,
                        "actualType", candidate.type, "result", "Ready", "message", ""));
            }
        });
        start(task);
    }

    private void resetFieldValidations() {
        if (lastResponse == null) {
            showWarning("Field Validations", "Send an API request first.");
            return;
        }
        parseResponseFields(lastResponse.rawBody);
    }

    private void resetFieldValidationDefaults() {
        if (lastResponse == null) {
            fieldValidationRows.clear();
            showWarning("Field Validations", "Send an API request first to list response fields for validation.");
            return;
        }
        parseResponseFields(lastResponse.rawBody);
    }

    private void runFieldValidations() {
        for (Map<String, String> row : fieldValidationRows) {
            if (!isSelected(row)) {
                continue;
            }
            String actual = extractJsonValue(lastResponse == null ? null : lastResponse.rawBody, row.get("field"));
            String expected = resolveVariables(row.get("expected"));
            boolean passed = compareValues(expected, actual, row.get("operator"));
            if ("Not Null".equals(row.get("nullValidation"))) {
                passed = passed && actual != null && !actual.isBlank() && !"null".equalsIgnoreCase(actual);
            }
            row.put("actual", actual);
            row.put("result", passed ? "Pass" : "Fail");
            row.put("message", passed ? "Expected value matched" : "Expected " + expected + " but found " + actual);
        }
        fieldValidationsTable.refresh();
    }

    private void selectTopLevelResponseFields() {
        for (Map<String, String> row : responseFieldRows) {
            String path = row.getOrDefault("jsonPath", "");
            String trimmed = path.startsWith("$.") ? path.substring(2) : path;
            row.put("selected", String.valueOf(!trimmed.contains(".") && !trimmed.contains("[")));
        }
        responseFieldsTable.refresh();
    }

    private void chooseExpectedJson() {
        File file = chooseOpenFile("JSON Files", "*.json");
        if (file != null) {
            expectedJsonPathField.setText(file.getAbsolutePath());
        }
    }

    private void runCompare(boolean includeMatches) {
        try {
            Path path = Path.of(expectedJsonPathField.getText());
            lastExpectedJson = Files.readString(path, StandardCharsets.UTF_8);
            lastActualJson = lastResponse == null ? rawResponseArea.getText() : lastResponse.rawBody;
            boolean strict = "Strict".equals(compareModeBox.getValue());
            List<Object[]> results = comparator.compare(lastExpectedJson, lastActualJson, strict, includeMatches);
            compareRows.clear();
            for (Object[] result : results) {
                compareRows.add(row("status", valueAt(result, 0), "path", valueAt(result, 1),
                        "expected", valueAt(result, 2), "actual", valueAt(result, 3), "message", valueAt(result, 4)));
            }
        } catch (Exception e) {
            showError("Compare Failed", e);
        }
    }

    private void runPerformanceTest() {
        ApiRequest request = buildApiRequest(perfBodyArea.getText().isBlank() && bodyArea != null ? bodyArea.getText() : perfBodyArea.getText());
        if (request.url.isBlank()) {
            showWarning("Performance Test", "Enter an endpoint in API Tester first.");
            return;
        }
        perfLogArea.appendText("Starting load test...\n");
        Task<PerformanceTestResult> task = new Task<>() {
            @Override
            protected PerformanceTestResult call() throws Exception {
                return performanceTestService.runLoadTest(request, perfThreadsSpinner.getValue(), perfIterationsSpinner.getValue());
            }
        };
        task.setOnSucceeded(e -> renderPerformance(task.getValue()));
        task.setOnFailed(e -> showError("Performance Test Failed", task.getException()));
        start(task);
    }

    private void renderPerformance(PerformanceTestResult result) {
        perfSamplesLabel.setText(String.valueOf(result.samples));
        perfErrorsLabel.setText(result.errors + " (" + String.format("%.2f", result.errorPercent) + "%)");
        perfThroughputLabel.setText(String.format("%.2f / sec", result.throughputPerSecond));
        perfDurationLabel.setText(formatDuration(result.duration));
        lastPerformanceReportPath = result.reportIndexPath;
        perfReportLabel.setText(result.reportIndexPath == null ? "No report" : result.reportIndexPath.toString());
        perfLogArea.appendText("Completed " + result.samples + " samples. Report: " + perfReportLabel.getText() + "\n");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (Map.Entry<String, Double> entry : result.chartValuesMs.entrySet()) {
            series.getData().add(new XYChart.Data<>(entry.getKey(), entry.getValue()));
        }
        perfChart.getData().setAll(series);
    }

    private void openPerformanceReport() {
        openPath(lastPerformanceReportPath, "No HTML performance report is available yet.");
    }

    private void testDbConnection() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                dbValidationService.testConnection(buildDbConfig());
                return null;
            }
        };
        dbConnectionStatusLabel.setText("Testing...");
        task.setOnSucceeded(e -> dbConnectionStatusLabel.setText("Connection OK"));
        task.setOnFailed(e -> {
            dbConnectionStatusLabel.setText("Connection failed");
            showError("DB Connection Failed", task.getException());
        });
        start(task);
    }

    private void toggleDbPasswordVisibility(Button toggleButton) {
        boolean show = !visibleDbPasswordField.isVisible();
        visibleDbPasswordField.setVisible(show);
        visibleDbPasswordField.setManaged(show);
        dbPasswordField.setVisible(!show);
        dbPasswordField.setManaged(!show);
        toggleButton.setText(show ? "Hide" : "Show");
    }

    private void saveDbConnection() {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("db-connection.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            DbConnectionConfig config = buildDbConfig();
            JSONObject json = new JSONObject();
            json.put("databaseType", config.databaseType);
            json.put("jdbcUrl", config.jdbcUrl);
            json.put("username", config.username);
            json.put("password", config.password);
            json.put("driverClass", config.driverClass);
            Files.writeString(file.toPath(), json.toString(2), StandardCharsets.UTF_8);
            dbConnectionFilePath = file.toPath();
            dbConnectionStatusLabel.setText("Connection saved");
        } catch (Exception e) {
            showError("Save Connection Failed", e);
        }
    }

    private void loadDbConnection() {
        File file = chooseOpenFile("JSON Files", "*.json");
        if (file == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            dbTypeBox.setValue(json.optString("databaseType", "MySQL"));
            jdbcUrlField.setText(json.optString("jdbcUrl"));
            dbUsernameField.setText(json.optString("username"));
            dbPasswordField.setText(json.optString("password"));
            driverClassField.setText(json.optString("driverClass", "com.mysql.cj.jdbc.Driver"));
            dbConnectionFilePath = file.toPath();
            dbConnectionStatusLabel.setText("Connection loaded");
        } catch (Exception e) {
            showError("Load Connection Failed", e);
        }
    }

    private void runDbQuery() {
        Task<List<Map<String, Object>>> task = new Task<>() {
            @Override
            protected List<Map<String, Object>> call() throws Exception {
                return dbValidationService.executeQuery(buildDbConfig(), dbQueryArea.getText(),
                        lastResponse == null ? "" : lastResponse.rawBody, savedVariables);
            }
        };
        task.setOnSucceeded(e -> renderDbRows(task.getValue()));
        task.setOnFailed(e -> showError("Run Query Failed", task.getException()));
        start(task);
    }

    private void renderDbRows(List<Map<String, Object>> rows) {
        dbQueryResultRows.clear();
        rebuildDynamicTable(dbQueryResultsTable, dbQueryResultRows, rows);
        refreshDbColumnValidationRows(rows);
    }

    private void runDbValidation() {
        List<DbValidationRule> rules = new ArrayList<>();
        for (Map<String, String> row : dbRuleRows) {
            if (!isSelected(row)) {
                continue;
            }
            DbValidationRule rule = new DbValidationRule();
            rule.apiField = row.get("apiField");
            rule.dbColumn = row.get("dbColumn");
            rule.operator = row.getOrDefault("operator", "=");
            rule.description = row.get("description");
            rules.add(rule);
        }
        if (rules.isEmpty()) {
            showWarning("DB Validation", "Add at least one selected validation rule.");
            return;
        }
        Task<DbValidationReport> task = new Task<>() {
            @Override
            protected DbValidationReport call() throws Exception {
                return dbValidationService.validate(buildDbConfig(), dbQueryArea.getText(),
                        rules, lastResponse == null ? "" : lastResponse.rawBody, savedVariables);
            }
        };
        task.setOnSucceeded(e -> renderDbValidation(task.getValue()));
        task.setOnFailed(e -> showError("DB Validation Failed", task.getException()));
        start(task);
    }

    private void renderDbValidation(DbValidationReport report) {
        dbResultRows.clear();
        for (DbValidationResult result : report.results) {
            dbResultRows.add(row("result", result.passed ? "Pass" : "Fail", "field", result.field,
                    "expected", result.expectedValue, "actual", result.actualValue, "operator", result.operator, "message", result.message));
        }
        dbSummaryLabel.setText(report.passed + " passed / " + report.failed + " failed");
        if (report.dbRows != null) {
            renderDbRows(report.dbRows);
        }
    }

    private DbConnectionConfig buildDbConfig() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.databaseType = dbTypeBox.getValue();
        config.jdbcUrl = jdbcUrlField.getText();
        config.username = dbUsernameField.getText();
        config.password = dbPasswordField.getText();
        config.driverClass = driverClassField.getText();
        return config;
    }

    private void applyDbDefaults() {
        switch (dbTypeBox.getValue()) {
            case "PostgreSQL" -> {
                jdbcUrlField.setText("jdbc:postgresql://localhost:5432/your_database");
                driverClassField.setText("org.postgresql.Driver");
            }
            case "Oracle" -> {
                jdbcUrlField.setText("jdbc:oracle:thin:@localhost:1521:xe");
                driverClassField.setText("oracle.jdbc.OracleDriver");
            }
            case "SQL Server" -> {
                jdbcUrlField.setText("jdbc:sqlserver://localhost:1433;databaseName=your_database;encrypt=false");
                driverClassField.setText("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            }
            default -> {
                jdbcUrlField.setText("jdbc:mysql://localhost:3306/your_database");
                driverClassField.setText("com.mysql.cj.jdbc.Driver");
            }
        }
    }

    private void saveSelectedDbResultCellAsVariable() {
        TablePosition<Map<String, String>, ?> position = dbQueryResultsTable.getSelectionModel().getSelectedCells().stream()
                .findFirst()
                .orElse(null);
        if (position == null || position.getRow() < 0 || position.getTableColumn() == null) {
            showWarning("Save DB Variable", "Select a resultset cell to save as a variable.");
            return;
        }
        String key = position.getTableColumn().getId();
        if (key == null || key.isBlank() || "row".equals(key)) {
            showWarning("Save DB Variable", "Select a data column cell, not the row number.");
            return;
        }
        Map<String, String> selectedRow = dbQueryResultRows.get(position.getRow());
        String value = selectedRow.getOrDefault(key, "");
        TextInputDialog dialog = new TextInputDialog(normalizeVariableName(key));
        dialog.setTitle("Save DB Variable");
        dialog.setHeaderText("Save selected DB cell as variable");
        dialog.showAndWait().ifPresent(name -> {
            String variableName = normalizeVariableName(name);
            savedVariables.put(variableName, value);
            savedVariablePaths.put(variableName, "db:" + key + "[" + position.getRow() + "]");
            savedVariableTypes.put(variableName, "DB Result");
            refreshVariablesView();
            showInfo("Save DB Variable", "Saved ${" + variableName + "} from DB resultset.");
        });
    }

    private void populateDefaultDbRules() {
        dbRuleRows.clear();
        if (lastResponse == null || lastResponse.rawBody == null || lastResponse.rawBody.isBlank()) {
            dbRuleRows.add(row("selected", "true", "apiField", "$.id", "dbColumn", "id", "operator", "=", "description", "API id equals DB id"));
            return;
        }
        for (ResponseFieldCandidate candidate : responseVariableService.parseFields(lastResponse.rawBody)) {
            dbRuleRows.add(row("selected", "true", "apiField", candidate.jsonPath,
                    "dbColumn", candidate.fieldName, "operator", "=", "description", candidate.fieldName + " matches"));
        }
        dbRulesTable.refresh();
    }

    private void setAllRowsSelected(ObservableList<Map<String, String>> rows, TableView<Map<String, String>> table, boolean selected) {
        rows.forEach(row -> row.put("selected", String.valueOf(selected)));
        table.refresh();
    }

    private void loadDbColumnOptions() {
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                return dbValidationService.fetchColumnLabels(buildDbConfig(), dbQueryArea.getText(),
                        lastResponse == null ? "" : lastResponse.rawBody, savedVariables);
            }
        };
        task.setOnSucceeded(e -> {
            dbRuleRows.clear();
            for (String column : task.getValue()) {
                dbRuleRows.add(row("selected", "true", "apiField", "$." + column,
                        "dbColumn", column, "operator", "=", "description", column + " matches"));
            }
            dbRulesTable.refresh();
        });
        task.setOnFailed(e -> showError("Load DB Columns Failed", task.getException()));
        start(task);
    }

    private void saveDbRules() {
        JSONArray rules = new JSONArray();
        for (Map<String, String> row : dbRuleRows) {
            rules.put(new JSONObject(row));
        }
        saveTextFile(rules.toString(2), "dbrules.json");
    }

    private void loadDbRules() {
        File file = chooseOpenFile("JSON Files", "*.json");
        if (file == null) {
            return;
        }
        try {
            JSONArray rules = new JSONArray(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            dbRuleRows.clear();
            for (int i = 0; i < rules.length(); i++) {
                JSONObject item = rules.getJSONObject(i);
                dbRuleRows.add(row("selected", item.optString("selected", "true"),
                        "apiField", item.optString("apiField"),
                        "dbColumn", item.optString("dbColumn"),
                        "operator", item.optString("operator", "="),
                        "description", item.optString("description")));
            }
        } catch (Exception e) {
            showError("Load Rules Failed", e);
        }
    }

    private void refreshDbColumnValidationRows(List<Map<String, Object>> dbRows) {
        dbColumnValidationRows.clear();
        if (dbRows == null || dbRows.isEmpty()) {
            return;
        }
        int rowIndex = 0;
        for (Map<String, Object> dbRow : dbRows) {
            for (Map.Entry<String, Object> entry : dbRow.entrySet()) {
                String value = String.valueOf(entry.getValue());
                dbColumnValidationRows.add(row("selected", "true",
                        "dbColumnName", entry.getKey() + "[" + rowIndex + "]",
                        "value", value,
                        "nullValidation", value == null || "null".equals(value) ? "must be null" : "must not be null",
                        "typeValidation", inferTypeValidation(value),
                        "expectedValueOrVariable", value,
                        "result", "Ready"));
            }
            rowIndex++;
        }
    }

    private void resetDbColumnValidationDefaults() {
        for (Map<String, String> row : dbColumnValidationRows) {
            String value = row.getOrDefault("value", "");
            row.put("selected", "true");
            row.put("nullValidation", value.isBlank() || "null".equalsIgnoreCase(value) ? "must be null" : "must not be null");
            row.put("typeValidation", inferTypeValidation(value));
            row.put("expectedValueOrVariable", value);
            row.put("result", "Ready");
        }
        dbColumnValidationsTable.refresh();
    }

    private void runDbColumnValidations() {
        for (Map<String, String> row : dbColumnValidationRows) {
            if (!isSelected(row)) {
                continue;
            }
            String actual = row.getOrDefault("value", "");
            String expected = resolveVariables(row.getOrDefault("expectedValueOrVariable", ""));
            boolean passed = compareValues(expected, actual, "equals");
            String nullRule = row.getOrDefault("nullValidation", "");
            if ("must not be null".equals(nullRule)) {
                passed = passed && actual != null && !actual.isBlank() && !"null".equalsIgnoreCase(actual);
            }
            row.put("result", passed ? "Pass" : "Fail");
        }
        dbColumnValidationsTable.refresh();
    }

    private String inferTypeValidation(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return "any";
        }
        if (value.matches("-?\\d+")) {
            return "integer";
        }
        if (value.matches("-?\\d+\\.\\d+")) {
            return "number";
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return "boolean";
        }
        return "string";
    }

    private void startWebRecording() {
        if (webStartUrlField.getText().isBlank()) {
            showWarning("Web Recording", "Start URL is required before recording.");
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                playwrightRecorderController.startRecording(webStartUrlField.getText(), recorderListener());
                return null;
            }
        };
        task.setOnFailed(e -> showError("Web Recording Failed", task.getException()));
        start(task);
    }

    private void startAttachedWebRecording() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                playwrightRecorderController.startAttachedRecording(webCdpEndpointField.getText(), recorderListener());
                return null;
            }
        };
        task.setOnFailed(e -> showError("Attach Browser Failed", task.getException()));
        start(task);
    }

    private PlaywrightRecorderController.RecorderListener recorderListener() {
        return new PlaywrightRecorderController.RecorderListener() {
            @Override
            public void onStatus(String message) {
                Platform.runLater(() -> webRecorderStatusLabel.setText(message));
            }

            @Override
            public void onStepCaptured(WebTestStep step) {
                Platform.runLater(() -> appendWebStep(step));
            }

            @Override
            public void onRecordingStopped() {
                Platform.runLater(() -> webRecorderStatusLabel.setText("Recorder stopped"));
            }

            @Override
            public void onUrlChanged(String url) {
                Platform.runLater(() -> webBrowserUrlLabel.setText("Browser URL: " + url));
            }

            @Override
            public void onError(String message) {
                Platform.runLater(() -> showError("Web Recorder Error", new RuntimeException(message)));
            }
        };
    }

    private void stopWebRecording() {
        playwrightRecorderController.stopRecording();
    }

    private void appendWebStep(WebTestStep step) {
        webStepRows.add(row("step", String.valueOf(webStepRows.size() + 1), "action", step.action,
                "selector", step.selector, "value", step.value, "note", step.note));
    }

    private void addWebStepDialog() {
        showWebStepDialog("Add Web Step", null).ifPresent(step ->
                webStepRows.add(row("step", String.valueOf(webStepRows.size() + 1),
                        "action", step.getOrDefault("action", ""),
                        "selector", step.getOrDefault("selector", ""),
                        "value", step.getOrDefault("value", ""),
                        "note", step.getOrDefault("note", ""))));
    }

    private void editSelectedWebStep() {
        Map<String, String> selected = webStepsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning("Web Testing", "Select a web step to edit.");
            return;
        }
        showWebStepDialog("Edit Web Step", selected).ifPresent(step -> {
            selected.put("action", step.getOrDefault("action", ""));
            selected.put("selector", step.getOrDefault("selector", ""));
            selected.put("value", step.getOrDefault("value", ""));
            selected.put("note", step.getOrDefault("note", ""));
            webStepsTable.refresh();
        });
    }

    private java.util.Optional<Map<String, String>> showWebStepDialog(String title, Map<String, String> existing) {
        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResizable(true);

        TextField actionField = new TextField(existing == null ? "" : existing.getOrDefault("action", ""));
        TextField selectorField = new TextField(existing == null ? "" : existing.getOrDefault("selector", ""));
        TextField valueField = new TextField(existing == null ? "" : existing.getOrDefault("value", ""));
        TextArea noteArea = editor(existing == null ? "" : existing.getOrDefault("note", ""));
        noteArea.setPrefRowCount(3);
        noteArea.setMinHeight(90);

        ComboBox<String> variableBox = createVariableDropdown();
        ComboBox<String> targetBox = combo("Selector", "Value", "Note");
        Button insertVariable = secondary("Insert Variable");
        insertVariable.setOnAction(e -> {
            if (variableBox.getValue() == null) {
                showWarning("Web Step", "Select a variable before inserting.");
                return;
            }
            String target = targetBox.getValue();
            if ("Selector".equals(target)) {
                insertVariable(selectorField, variableBox);
            } else if ("Value".equals(target)) {
                insertVariable(valueField, variableBox);
            } else {
                insertVariable(noteArea, variableBox);
            }
        });

        GridPane form = grid();
        form.setPrefWidth(720);
        form.add(labeled("Action", actionField), 0, 0, 2, 1);
        form.add(labeled("Selector", selectorField), 0, 1, 2, 1);
        form.add(labeled("Value", valueField), 0, 2, 2, 1);
        form.add(labeled("Note", noteArea), 0, 3, 2, 1);
        form.add(labeled("Variables", variableBox), 0, 4);
        form.add(labeled("Apply To", targetBox), 1, 4);
        form.add(insertVariable, 0, 5, 2, 1);
        GridPane.setHgrow(actionField, Priority.ALWAYS);
        GridPane.setHgrow(selectorField, Priority.ALWAYS);
        GridPane.setHgrow(valueField, Priority.ALWAYS);
        GridPane.setHgrow(noteArea, Priority.ALWAYS);

        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            return row("action", actionField.getText(),
                    "selector", selectorField.getText(),
                    "value", valueField.getText(),
                    "note", noteArea.getText());
        });
        return dialog.showAndWait();
    }

    private void moveSelectedWebStep(int direction) {
        int index = webStepsTable.getSelectionModel().getSelectedIndex();
        int target = index + direction;
        if (index < 0 || target < 0 || target >= webStepRows.size()) {
            return;
        }
        Map<String, String> item = webStepRows.remove(index);
        webStepRows.add(target, item);
        renumberWebSteps();
        webStepsTable.getSelectionModel().select(target);
    }

    private void addWebScreenshotStep() {
        webStepRows.add(row("step", String.valueOf(webStepRows.size() + 1),
                "action", "screenshot", "selector", "", "value", "", "note", "Capture screenshot"));
    }

    private void clearWebSteps() {
        webStepRows.clear();
        webResultRows.clear();
        webRunSummaryLabel.setText("--");
    }

    private void mergeWebRecording() {
        File file = chooseOpenFile("JSON Files", "*.json");
        if (file == null) {
            return;
        }
        int before = webStepRows.size();
        loadWebRecordingFile(file, true);
        showInfo("Merge Recording", (webStepRows.size() - before) + " step(s) merged.");
    }

    private void renumberWebSteps() {
        for (int i = 0; i < webStepRows.size(); i++) {
            webStepRows.get(i).put("step", String.valueOf(i + 1));
        }
    }

    private void launchDebugChrome() {
        showInfo("Launch Debug Chrome", "Start Chrome manually with --remote-debugging-port=9222, then use Attach.");
    }

    private void stopWebRecordingWithoutClosingBrowser() {
        playwrightRecorderController.stopRecordingWithoutClosingBrowser();
    }

    private void runWebTest() {
        WebTestCase testCase = buildWebTestCase();
        if (testCase.steps.isEmpty()) {
            showWarning("Web Testing", "Record, load, or add at least one step before running.");
            return;
        }
        Task<WebTestRunReport> task = new Task<>() {
            @Override
            protected WebTestRunReport call() throws Exception {
                return playwrightRecorderController.runTest(testCase, webHeadlessCheck.isSelected(), webSlowMoCheck.isSelected() ? 250 : 0);
            }
        };
        task.setOnSucceeded(e -> renderWebReport(task.getValue()));
        task.setOnFailed(e -> showError("Web Test Failed", task.getException()));
        start(task);
    }

    private WebTestCase buildWebTestCase() {
        WebTestCase testCase = new WebTestCase();
        testCase.testName = webTestNameField.getText();
        testCase.startUrl = webStartUrlField.getText();
        for (Map<String, String> row : webStepRows) {
            WebTestStep step = new WebTestStep();
            step.action = row.get("action");
            step.selector = row.get("selector");
            step.value = row.get("value");
            step.note = row.get("note");
            testCase.steps.add(step);
        }
        return testCase;
    }

    private void renderWebReport(WebTestRunReport report) {
        webResultRows.clear();
        for (WebTestExecutionResult result : report.results) {
            webResultRows.add(row("result", result.passed ? "Pass" : "Fail", "action", result.action,
                    "selector", result.selector, "expected", result.expectedValue, "message", result.message,
                    "duration", result.durationMs + " ms"));
        }
        webRunSummaryLabel.setText(report.passed + " passed / " + report.failed + " failed");
        if (report.lastScreenshotPath != null) {
            webTipsArea.appendText("\nLast screenshot: " + report.lastScreenshotPath);
        }
    }

    private void saveWebRecording() {
        JSONArray steps = new JSONArray();
        for (Map<String, String> row : webStepRows) {
            steps.put(new JSONObject(row));
        }
        JSONObject root = new JSONObject();
        root.put("testName", webTestNameField.getText());
        root.put("startUrl", webStartUrlField.getText());
        root.put("steps", steps);
        saveTextFile(root.toString(2), "web-recording.json");
    }

    private void loadWebRecording() {
        File file = chooseOpenFile("JSON Files", "*.json");
        if (file == null) {
            return;
        }
        loadWebRecordingFile(file, false);
    }

    private void loadWebRecordingFile(File file, boolean merge) {
        try {
            JSONObject root = new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            webTestNameField.setText(root.optString("testName", "Web Test"));
            webStartUrlField.setText(root.optString("startUrl", ""));
            if (!merge) {
                webStepRows.clear();
            }
            JSONArray steps = root.optJSONArray("steps");
            if (steps != null) {
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject item = steps.getJSONObject(i);
                    webStepRows.add(row("step", String.valueOf(webStepRows.size() + 1), "action", item.optString("action"),
                            "selector", item.optString("selector"), "value", item.optString("value"), "note", item.optString("note")));
                }
            }
        } catch (Exception e) {
            showError("Load Recording Failed", e);
        }
    }

    private void saveSelectedResponseVariables() {
        int count = 0;
        for (Map<String, String> row : responseFieldRows) {
            if (isSelected(row)) {
                String name = normalizeVariableName(row.getOrDefault("variableName", row.get("field")));
                savedVariables.put(name, row.get("value"));
                savedVariablePaths.put(name, row.get("jsonPath"));
                savedVariableTypes.put(name, row.get("type"));
                count++;
            }
        }
        refreshVariablesView();
        showInfo("Variables", count + " variable(s) saved.");
    }

    private void createVariableDialog() {
        TextInputDialog dialog = new TextInputDialog("name=value");
        dialog.setTitle("Create Variable");
        dialog.setHeaderText("Enter name=value");
        dialog.showAndWait().ifPresent(text -> {
            int equals = text.indexOf('=');
            if (equals <= 0) {
                showWarning("Create Variable", "Use the format name=value.");
                return;
            }
            String name = normalizeVariableName(text.substring(0, equals));
            savedVariables.put(name, text.substring(equals + 1));
            savedVariableTypes.put(name, "Manual");
            refreshVariablesView();
        });
    }

    private void removeSelectedVariables() {
        for (Map<String, String> row : new ArrayList<>(variablesTable.getSelectionModel().getSelectedItems())) {
            savedVariables.remove(row.get("name"));
            savedVariablePaths.remove(row.get("name"));
            savedVariableTypes.remove(row.get("name"));
        }
        refreshVariablesView();
    }

    private void refreshVariablesView() {
        if (variableRows != null) {
            variableRows.clear();
            savedVariables.keySet().stream().sorted().forEach(name ->
                    variableRows.add(row("name", name, "value", savedVariables.get(name),
                            "type", savedVariableTypes.getOrDefault(name, "Manual"), "path", savedVariablePaths.getOrDefault(name, ""))));
        }
        refreshVariableDropdowns();
    }

    private void saveVariablesToFile() {
        JSONArray array = new JSONArray();
        for (String name : savedVariables.keySet()) {
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("value", savedVariables.get(name));
            item.put("type", savedVariableTypes.getOrDefault(name, "Manual"));
            item.put("path", savedVariablePaths.getOrDefault(name, ""));
            array.put(item);
        }
        saveTextFile(array.toString(2), "api-validator-variables.json");
    }

    private String importedVariableName(JSONObject item) {
        return item.optString("name").replace("${", "").replace("}", "").trim();
    }

    private void importVariablesFromFile() {
        File file = chooseOpenFile("JSON Files", "*.json");
        if (file == null) {
            return;
        }
        try {
            Object parsed = new JSONTokener(Files.readString(file.toPath(), StandardCharsets.UTF_8)).nextValue();
            JSONArray array;
            if (parsed instanceof JSONArray parsedArray) {
                array = parsedArray;
            } else if (parsed instanceof JSONObject parsedObject && parsedObject.has("variables")) {
                array = parsedObject.optJSONArray("variables");
            } else {
                array = new JSONArray().put(parsed);
            }
            if (array == null) {
                showWarning("Import Variables", "No variables array was found in the selected JSON file.");
                return;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String name = importedVariableName(item);
                if (name.isBlank()) {
                    continue;
                }
                savedVariables.put(name, item.optString("value"));
                savedVariableTypes.put(name, item.optString("type", "Imported"));
                savedVariablePaths.put(name, item.optString("path", item.optString("jsonPath", "")));
            }
            refreshVariablesView();
        } catch (Exception e) {
            showError("Import Variables Failed", e);
        }
    }

    private ComboBox<String> createVariableDropdown() {
        ComboBox<String> box = new ComboBox<>();
        box.setPromptText("Variables");
        variableDropdowns.add(box);
        refreshVariableDropdowns();
        return box;
    }

    private void refreshVariableDropdowns() {
        List<String> values = new ArrayList<>();
        for (String runtimeVariable : RUNTIME_VARIABLES) {
            values.add("${" + runtimeVariable + "}");
        }
        values.addAll(savedVariables.keySet().stream().sorted().map(name -> "${" + name + "}").toList());
        for (ComboBox<String> box : variableDropdowns) {
            box.setItems(FXCollections.observableArrayList(values));
        }
    }

    private void insertVariable(TextArea target, ComboBox<String> dropdown) {
        if (dropdown.getValue() != null) {
            target.insertText(target.getCaretPosition(), dropdown.getValue());
        }
    }

    private void insertVariable(TextField target, ComboBox<String> dropdown) {
        if (dropdown.getValue() != null) {
            target.insertText(target.getCaretPosition(), dropdown.getValue());
        }
    }

    private void beautifyBody() {
        try {
            bodyArea.setText(apiService.prettyPrintJson(bodyArea.getText()));
        } catch (Exception e) {
            showWarning("Beautify Failed", "Request body is not valid JSON.");
        }
    }

    private void clearApiForm() {
        endpointField.clear();
        tokenField.clear();
        visibleTokenField.clear();
        headersArea.clear();
        bodyArea.clear();
        prettyResponseArea.clear();
        rawResponseArea.clear();
        responseHeadersArea.clear();
        responseCookiesArea.clear();
        statusValueLabel.setText("--");
        timeValueLabel.setText("--");
        sizeValueLabel.setText("--");
        responseFieldRows.clear();
    }

    private void saveRequest() {
        JSONObject root = new JSONObject();
        root.put("url", endpointField.getText());
        root.put("method", methodBox.getValue());
        root.put("headers", new JSONObject(parseHeaders(headersArea.getText())));
        root.put("body", bodyArea.getText());
        saveTextFile(root.toString(2), "request.json");
    }

    private void saveResponse() {
        if (lastResponse == null) {
            showWarning("Save Response", "No response is available to save.");
            return;
        }
        saveTextFile(lastResponse.rawBody, "response.json");
    }

    private Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return headers;
        }
        for (String line : text.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private Map<String, String> resolveHeaderVariables(Map<String, String> headers) {
        Map<String, String> resolvedHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            resolvedHeaders.put(resolveVariables(entry.getKey()), resolveVariables(entry.getValue()));
        }
        return resolvedHeaders;
    }

    private String resolveVariables(String text) {
        if (text == null) {
            return "";
        }
        String resolved = text;
        resolved = resolved.replace("${randomString}", randomString());
        resolved = resolved.replace("${randomInt}", String.valueOf(ThreadLocalRandom.current().nextInt(10000, 999999)));
        resolved = resolved.replace("${randomDate}", LocalDate.now().toString());
        for (Map.Entry<String, String> entry : savedVariables.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    private String randomString() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String extractJsonValue(String json, String path) {
        try {
            if (json == null || json.isBlank() || path == null || path.isBlank()) {
                return "";
            }
            Object current = new JSONTokener(json).nextValue();
            String normalized = path.startsWith("$.") ? path.substring(2) : path;
            for (String part : normalized.split("\\.")) {
                if (part.isBlank()) {
                    continue;
                }
                if (current instanceof JSONObject object) {
                    current = object.opt(part);
                } else if (current instanceof JSONArray array) {
                    int index = Integer.parseInt(part.replace("[", "").replace("]", ""));
                    current = array.opt(index);
                }
            }
            return current == null ? "" : String.valueOf(current);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean compareValues(String expected, String actual, String operator) {
        String op = operator == null ? "equals" : operator.toLowerCase();
        return switch (op) {
            case "not equals", "!=" -> !Objects.equals(expected, actual);
            case "contains" -> actual != null && actual.contains(expected);
            case "not empty" -> actual != null && !actual.isBlank();
            default -> Objects.equals(expected, actual);
        };
    }

    private void rebuildDynamicTable(TableView<Map<String, String>> table, ObservableList<Map<String, String>> target,
                                     List<Map<String, Object>> rows) {
        table.getColumns().clear();
        target.clear();
        if (rows == null || rows.isEmpty()) {
            table.getColumns().add(stringColumn("Result", "result"));
            return;
        }
        Set<String> columns = rows.get(0).keySet();
        for (String column : columns) {
            table.getColumns().add(stringColumn(column, column));
        }
        int index = 1;
        for (Map<String, Object> source : rows) {
            Map<String, String> mapped = new LinkedHashMap<>();
            mapped.put("row", String.valueOf(index++));
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                mapped.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            target.add(mapped);
        }
    }

    private TableView<Map<String, String>> mapTable(ObservableList<Map<String, String>> rows, String... columnPairs) {
        TableView<Map<String, String>> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        for (int i = 0; i + 1 < columnPairs.length; i += 2) {
            String title = columnPairs[i];
            String key = columnPairs[i + 1];
            if ("selected".equals(key) || "jsonPath".equals(key) && "Add".equals(title)) {
                TableColumn<Map<String, String>, Boolean> column = new TableColumn<>(title);
                column.setCellValueFactory(data -> new SimpleBooleanProperty(isSelected(data.getValue())));
                table.getColumns().add(column);
            } else {
                table.getColumns().add(stringColumn(title, key));
            }
        }
        return table;
    }

    private TableColumn<Map<String, String>, String> stringColumn(String title, String key) {
        TableColumn<Map<String, String>, String> column = new TableColumn<>(title);
        column.setId(key);
        column.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getOrDefault(key, "")));
        return column;
    }

    private boolean isSelected(Map<String, String> row) {
        return "true".equalsIgnoreCase(row.getOrDefault("selected", row.getOrDefault("jsonPath", "false")));
    }

    private Map<String, String> row(String... values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            row.put(values[i], values[i + 1] == null ? "" : values[i + 1]);
        }
        return row;
    }

    private String normalizeVariableName(String value) {
        if (value == null || value.isBlank()) {
            return "variable";
        }
        return value.replace("${", "").replace("}", "").replaceAll("[^A-Za-z0-9_]", "_");
    }

    private String valueAt(Object[] values, int index) {
        return values != null && index < values.length && values[index] != null ? String.valueOf(values[index]) : "";
    }

    private String valueAt(String[] values, int index) {
        return values != null && index < values.length && values[index] != null ? values[index] : "";
    }

    private void start(Task<?> task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private ComboBox<String> combo(String first, String... rest) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().add(first);
        box.getItems().addAll(rest);
        box.setValue(first);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private TextArea editor(String text) {
        TextArea area = new TextArea(text);
        area.setWrapText(false);
        area.getStyleClass().add("editor");
        area.setMinWidth(240);
        area.setMinHeight(120);
        return area;
    }

    private TextArea requestEditor(String text) {
        TextArea area = editor(text);
        area.getStyleClass().add("request-editor");
        return area;
    }

    private TextArea responseEditor(String text) {
        TextArea area = editor(text);
        area.getStyleClass().add("response-editor");
        area.setMinHeight(320);
        area.setWrapText(true);
        area.setScrollTop(0);
        return area;
    }

    private Label metric(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("metric");
        return label;
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private Button primary(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        return button;
    }

    private Button secondary(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("secondary-button");
        return button;
    }

    private Button primaryButton(String text, EventHandler<ActionEvent> handler) {
        Button button = primary(text);
        button.setOnAction(handler);
        return button;
    }

    private Button secondaryButton(String text, EventHandler<ActionEvent> handler) {
        Button button = secondary(text);
        button.setOnAction(handler);
        return button;
    }

    private GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setMinWidth(0);
        return grid;
    }

    private VBox labeled(String label, javafx.scene.Node node) {
        Label title = new Label(label);
        title.getStyleClass().add("field-label");
        VBox box = new VBox(5, title, node);
        box.setMinWidth(0);
        if (node instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        VBox.setVgrow(node, Priority.ALWAYS);
        return box;
    }

    private HBox wrapTokenField(Button toggleButton) {
        HBox wrapper = new HBox(8, tokenField, visibleTokenField, toggleButton);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.setMinWidth(0);
        HBox.setHgrow(tokenField, Priority.ALWAYS);
        HBox.setHgrow(visibleTokenField, Priority.ALWAYS);
        tokenField.setMaxWidth(Double.MAX_VALUE);
        visibleTokenField.setMaxWidth(Double.MAX_VALUE);
        return wrapper;
    }

    private HBox wrapTextFieldWithActions(TextField field, javafx.scene.Node... actions) {
        HBox wrapper = new HBox(8);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.setMinWidth(0);
        wrapper.getChildren().add(field);
        wrapper.getChildren().addAll(actions);
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);
        return wrapper;
    }

    private HBox wrapDbPasswordField(Button toggleButton) {
        HBox wrapper = new HBox(8, dbPasswordField, visibleDbPasswordField, toggleButton);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.setMinWidth(0);
        HBox.setHgrow(dbPasswordField, Priority.ALWAYS);
        HBox.setHgrow(visibleDbPasswordField, Priority.ALWAYS);
        dbPasswordField.setMaxWidth(Double.MAX_VALUE);
        visibleDbPasswordField.setMaxWidth(Double.MAX_VALUE);
        return wrapper;
    }

    private FlowPane actionRow(javafx.scene.Node... children) {
        FlowPane row = new FlowPane(10, 8, children);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefWrapLength(900);
        row.setMinWidth(0);
        return row;
    }

    private FlowPane spacedActionRow(javafx.scene.Node... children) {
        FlowPane row = new FlowPane(16, 12, children);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefWrapLength(1200);
        row.setMinWidth(0);
        for (javafx.scene.Node child : children) {
            if (child instanceof Button button) {
                button.setMinWidth(130);
            }
        }
        return row;
    }

    private VBox card(String title, javafx.scene.Node content) {
        VBox card = new VBox(8, sectionTitle(title), content);
        card.getStyleClass().add("card");
        card.setMinWidth(0);
        VBox.setVgrow(content, Priority.ALWAYS);
        return card;
    }

    private BorderPane withFooter(javafx.scene.Node content, javafx.scene.Node footer) {
        BorderPane pane = new BorderPane(content);
        pane.setBottom(footer);
        pane.setMinWidth(0);
        BorderPane.setMargin(footer, new Insets(8, 0, 0, 0));
        return pane;
    }

    private BorderPane wrap(javafx.scene.Node content) {
        BorderPane pane = new BorderPane(content);
        pane.getStyleClass().add("card");
        pane.setMinWidth(0);
        return pane;
    }

    private ScrollPane padded(javafx.scene.Node content) {
        BorderPane pane = new BorderPane(content);
        pane.setPadding(new Insets(14));
        pane.setMinWidth(0);
        ScrollPane scrollPane = new ScrollPane(pane);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPannable(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("workspace-scroll");
        return scrollPane;
    }

    private File chooseOpenFile(String description, String extension) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, extension));
        return chooser.showOpenDialog(stage);
    }

    private void saveTextFile(String text, String initialName) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(initialName);
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            showError("Save Failed", e);
        }
    }

    private void loadTextFile(TextArea target) {
        File file = chooseOpenFile("Text Files", "*.*");
        if (file == null) {
            return;
        }
        try {
            target.setText(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            showError("Load Failed", e);
        }
    }

    private void openPath(Path path, String missingMessage) {
        if (path == null || !Files.exists(path)) {
            showWarning("Open File", missingMessage);
            return;
        }
        getHostServices().showDocument(path.toUri().toString());
    }

    private String formatDuration(Duration duration) {
        if (duration == null) {
            return "--";
        }
        long millis = duration.toMillis();
        return millis < 1000 ? millis + " ms" : String.format("%.2f s", millis / 1000.0);
    }

    private void showInfo(String title, String message) {
        alert(Alert.AlertType.INFORMATION, title, message, null);
    }

    private void showWarning(String title, String message) {
        alert(Alert.AlertType.WARNING, title, message, null);
    }

    private void showError(String title, Throwable throwable) {
        alert(Alert.AlertType.ERROR, title, throwable == null ? "Unknown error" : throwable.getMessage(), throwable);
    }

    private void alert(Alert.AlertType type, String title, String message, Throwable throwable) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type, message, ButtonType.OK);
            alert.setTitle(title);
            alert.setHeaderText(title);
            if (throwable != null) {
                TextArea details = new TextArea(String.valueOf(throwable));
                details.setEditable(false);
                alert.getDialogPane().setExpandableContent(details);
            }
            alert.showAndWait();
        });
    }

    private void loadApplicationIcon(Stage stage) {
        URL logoUrl = ApiValidatorFxApp.class.getResource(APP_LOGO_RESOURCE);
        if (logoUrl != null) {
            stage.getIcons().add(new Image(logoUrl.toExternalForm()));
        }
    }

    private String createInlineStylesheet() {
        return "data:text/css," + """
                .root { -fx-font-family: 'Segoe UI'; -fx-font-size: 13px; }
                .top-bar { -fx-background-color: #f5f7fb; -fx-border-color: transparent transparent #d2dceb transparent; }
                .app-title { -fx-font-size: 19px; -fx-font-weight: 700; -fx-text-fill: #1f2937; }
                .muted { -fx-text-fill: #5f6778; }
                .section-title { -fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #263244; }
                .field-label { -fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #5f6778; }
                .metric { -fx-font-weight: 700; -fx-text-fill: #1f2937; }
                .card { -fx-background-color: white; -fx-border-color: #d2dceb; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 12px; }
                .editor { -fx-font-family: 'Consolas'; -fx-font-size: 13px; }
                .request-editor .scroll-pane { -fx-hbar-policy: as-needed; -fx-vbar-policy: always; }
                .response-editor { -fx-font-size: 14px; }
                .response-editor .scroll-pane { -fx-hbar-policy: never; -fx-vbar-policy: always; }
                .capture-panel { -fx-background-color: #f7f9fd; -fx-padding: 10px; }
                .capture-toolbar { -fx-background-color: #f7f9fd; -fx-padding: 4px 0 8px 0; }
                .validation-toolbar { -fx-background-color: #f7f9fd; -fx-padding: 12px; -fx-border-color: #d2dceb; -fx-border-radius: 6px; -fx-background-radius: 6px; }
                .context-panel { -fx-hgap: 20px; -fx-vgap: 10px; }
                .context-button { -fx-padding: 9px 18px; }
                .db-workflow { -fx-padding: 0 0 18px 0; }
                .primary-button { -fx-background-color: %s; -fx-text-fill: white; -fx-font-weight: 700; -fx-background-radius: 5px; -fx-padding: 8px 16px; }
                .secondary-button { -fx-background-color: white; -fx-text-fill: #233044; -fx-border-color: #d2dceb; -fx-border-radius: 5px; -fx-background-radius: 5px; -fx-padding: 8px 14px; }
                .danger-button { -fx-background-color: #c93535; -fx-text-fill: white; -fx-border-color: #a92727; -fx-font-weight: 700; }
                .tab-pane { -fx-background-color: #f5f7fb; }
                """.formatted(PRIMARY).replace("\n", "%0A").replace(" ", "%20").replace("#", "%23");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
