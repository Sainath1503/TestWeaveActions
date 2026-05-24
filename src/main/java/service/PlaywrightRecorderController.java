package service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import model.WebTestCase;
import model.WebTestExecutionResult;
import model.WebTestRunReport;
import model.WebTestStep;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PlaywrightRecorderController {

    private static final String RECORDER_SCRIPT = """
            (() => {
              const recorderVersion = '2';
              if (window.__apiValidatorRecorderInstalled && window.__apiValidatorRecorderVersion === recorderVersion) {
                return;
              }
              window.__apiValidatorRecorderInstalled = true;
              window.__apiValidatorRecorderVersion = recorderVersion;
              window.__apiValidatorRecordedEvents = window.__apiValidatorRecordedEvents || [];

              const lastValues = new WeakMap();

              const flash = (element) => {
                if (!element || !(element instanceof HTMLElement)) {
                  return;
                }
                const previousOutline = element.style.outline;
                const previousOffset = element.style.outlineOffset;
                element.style.outline = '2px solid #ef4444';
                element.style.outlineOffset = '2px';
                setTimeout(() => {
                  element.style.outline = previousOutline;
                  element.style.outlineOffset = previousOffset;
                }, 700);
              };

              const cssEscape = (value) => {
                if (window.CSS && window.CSS.escape) {
                  return window.CSS.escape(value);
                }
                return String(value).replace(/([ #;?%&,.+*~\\':"!^$\\[\\]()=>|/@])/g, '\\\\$1');
              };

              const isUnique = (selector) => {
                try {
                  return !!selector && document.querySelectorAll(selector).length === 1;
                } catch (e) {
                  return false;
                }
              };

              const roleFor = (element) => {
                const role = element.getAttribute('role');
                if (role) return role;
                const tag = element.tagName.toLowerCase();
                if (tag === 'button') return 'button';
                if (tag === 'a') return 'link';
                if (tag === 'input') {
                  const type = (element.getAttribute('type') || 'text').toLowerCase();
                  return type === 'submit' ? 'button' : 'textbox';
                }
                return null;
              };

              const generateSelector = (element) => {
                if (!element || !(element instanceof Element)) {
                  return 'body';
                }

                const testId = element.getAttribute('data-testid') || element.getAttribute('data-test') || element.getAttribute('data-qa');
                if (testId && isUnique(`[data-testid="${cssEscape(testId)}"]`)) {
                  return `[data-testid="${cssEscape(testId)}"]`;
                }

                if (element.id && isUnique(`#${cssEscape(element.id)}`)) {
                  return `#${cssEscape(element.id)}`;
                }

                const name = element.getAttribute('name');
                if (name && isUnique(`[name="${cssEscape(name)}"]`)) {
                  return `[name="${cssEscape(name)}"]`;
                }

                const ariaLabel = element.getAttribute('aria-label');
                if (ariaLabel && isUnique(`[aria-label="${cssEscape(ariaLabel)}"]`)) {
                  return `[aria-label="${cssEscape(ariaLabel)}"]`;
                }

                const placeholder = element.getAttribute('placeholder');
                if (placeholder && isUnique(`[placeholder="${cssEscape(placeholder)}"]`)) {
                  return `[placeholder="${cssEscape(placeholder)}"]`;
                }

                const role = roleFor(element);
                const text = (element.innerText || element.value || '').trim().replace(/\\s+/g, ' ');
                if (role && text && text.length <= 40) {
                  const candidate = `${element.tagName.toLowerCase()}:has-text("${text.replace(/"/g, '\\"')}")`;
                  if (isUnique(candidate)) {
                    return candidate;
                  }
                }

                const classList = Array.from(element.classList || []).filter(Boolean);
                if (classList.length > 0) {
                  const candidate = `${element.tagName.toLowerCase()}.${classList.slice(0, 2).map(cssEscape).join('.')}`;
                  if (isUnique(candidate)) {
                    return candidate;
                  }
                }

                const parts = [];
                let current = element;
                while (current && current.nodeType === 1 && current !== document.body) {
                  let part = current.tagName.toLowerCase();
                  if (current.id) {
                    part += `#${cssEscape(current.id)}`;
                    parts.unshift(part);
                    break;
                  }
                  const siblings = current.parentElement ? Array.from(current.parentElement.children)
                    .filter((child) => child.tagName === current.tagName) : [];
                  if (siblings.length > 1) {
                    part += `:nth-of-type(${siblings.indexOf(current) + 1})`;
                  }
                  parts.unshift(part);
                  current = current.parentElement;
                }
                return parts.join(' > ');
              };

              const emit = (payload) => {
                if (typeof window.__apiValidatorEmit !== 'function') {
                  window.__apiValidatorRecordedEvents.push(payload);
                  return;
                }
                window.__apiValidatorEmit(JSON.stringify(payload));
              };

              document.addEventListener('click', (event) => {
                const element = event.target instanceof Element ? event.target.closest('button, a, input, textarea, select, [role="button"], [role="link"], [onclick], [data-testid], label') || event.target : null;
                if (!element) {
                  return;
                }
                flash(element);
                emit({
                  action: 'Click',
                  selector: generateSelector(element),
                  value: '',
                  note: (element.innerText || element.getAttribute('aria-label') || '').trim()
                });
              }, true);

              document.addEventListener('change', (event) => {
                const element = event.target;
                if (!(element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement || element instanceof HTMLSelectElement)) {
                  return;
                }
                const currentValue = element.value || '';
                if (lastValues.get(element) === currentValue) {
                  return;
                }
                lastValues.set(element, currentValue);
                flash(element);
                if (element instanceof HTMLSelectElement) {
                  const selectedOption = element.options[element.selectedIndex];
                  emit({
                    action: 'Select Option',
                    selector: generateSelector(element),
                    value: currentValue,
                    note: selectedOption ? (selectedOption.innerText || '').trim() : ''
                  });
                  return;
                }
                emit({
                  action: 'Type',
                  selector: generateSelector(element),
                  value: currentValue,
                  note: element.type === 'password' ? 'password input' : ''
                });
              }, true);
            })();
            """;

    private final Object lock = new Object();
    private final List<WebTestStep> capturedSteps = new CopyOnWriteArrayList<>();

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private RecorderListener listener;
    private ScheduledExecutorService eventPoller;
    private boolean recording;
    private boolean attachedRecording;
    private volatile boolean webTestStopRequested;
    private Playwright activeRunPlaywright;
    private Browser activeRunBrowser;
    private BrowserContext activeRunContext;
    private Page activeRunPage;
    private boolean activeRunCloseBrowserOnStop;
    private String lastRecordedUrl;
    private String pendingNavigationSource;

    public interface RecorderListener {
        void onStatus(String message);

        void onStepCaptured(WebTestStep step);

        void onRecordingStopped();

        void onUrlChanged(String url);

        void onError(String message);
    }

    public void startRecording(String startUrl, RecorderListener recorderListener) throws Exception {
        synchronized (lock) {
            stopRecordingInternal(false, true);
            this.listener = recorderListener;
            this.recording = true;
            this.attachedRecording = false;
            this.capturedSteps.clear();
            this.lastRecordedUrl = null;
            this.pendingNavigationSource = null;

            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false).setSlowMo(125));
            context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 800));
            context.exposeBinding("__apiValidatorEmit", (source, args) -> {
                if (args == null || args.length == 0) {
                    return null;
                }
                handleBrowserPayload(String.valueOf(args[0]));
                return null;
            });
            context.addInitScript(RECORDER_SCRIPT);
            page = context.newPage();
            registerPage(page);
            context.onPage(this::registerPage);
            startEventPolling();

            emitStatus("Recording started. Use the Playwright browser window to interact with the page.");
            if (startUrl != null && !startUrl.isBlank()) {
                page.navigate(startUrl.trim());
            }
        }
    }

    public void startAttachedRecording(String cdpEndpoint, RecorderListener recorderListener) throws Exception {
        synchronized (lock) {
            stopRecordingInternal(false, true);
            this.listener = recorderListener;
            this.recording = true;
            this.attachedRecording = true;
            this.capturedSteps.clear();
            this.lastRecordedUrl = null;
            this.pendingNavigationSource = null;

            if (cdpEndpoint == null || cdpEndpoint.isBlank()) {
                throw new IllegalArgumentException("CDP endpoint is required. Example: http://localhost:9222");
            }

            playwright = Playwright.create();
            browser = connectOverCdpWithFallback(cdpEndpoint.trim());
            context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
            context.exposeBinding("__apiValidatorEmit", (source, args) -> {
                if (args == null || args.length == 0) {
                    return null;
                }
                handleBrowserPayload(String.valueOf(args[0]));
                return null;
            });
            context.addInitScript(RECORDER_SCRIPT);
            context.onPage(this::registerPage);

            List<Page> pages = context.pages();
            if (pages.isEmpty()) {
                page = context.newPage();
                registerPage(page);
            } else {
                page = pages.get(pages.size() - 1);
                for (Page openPage : pages) {
                    registerPage(openPage);
                    installRecorderOnPage(openPage);
                    handlePageLoaded(openPage);
                }
            }

            startEventPolling();
            emitStatus("Attached to active browser. Interact with the open browser window to record steps.");
        }
    }

    private Browser connectOverCdpWithFallback(String cdpEndpoint) {
        return connectOverCdpWithFallback(playwright, cdpEndpoint);
    }

    private Browser connectOverCdpWithFallback(Playwright cdpPlaywright, String cdpEndpoint) {
        try {
            return cdpPlaywright.chromium().connectOverCDP(cdpEndpoint);
        } catch (PlaywrightException firstFailure) {
            String ipv4Endpoint = cdpEndpoint.replace("://localhost", "://127.0.0.1");
            if (ipv4Endpoint.equals(cdpEndpoint)) {
                throw firstFailure;
            }
            try {
                return cdpPlaywright.chromium().connectOverCDP(ipv4Endpoint);
            } catch (PlaywrightException secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }

    public void stopRecording() {
        synchronized (lock) {
            stopRecordingInternal(true, true);
        }
    }

    public void stopRecordingWithoutClosingBrowser() {
        synchronized (lock) {
            stopRecordingInternal(false, false);
        }
    }

    public boolean isRecording() {
        synchronized (lock) {
            return recording;
        }
    }

    public WebTestRunReport runTest(WebTestCase testCase, boolean headless, int slowMoMillis) throws Exception {
        if (testCase == null) {
            throw new IllegalArgumentException("Web test case is required.");
        }
        if (testCase.steps == null || testCase.steps.isEmpty()) {
            throw new IllegalArgumentException("Add or record at least one web step before running the test.");
        }

        WebTestRunReport report = new WebTestRunReport();
        long suiteStart = System.currentTimeMillis();
        webTestStopRequested = false;

        try (Playwright runPlaywright = Playwright.create()) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setSlowMo((double) Math.max(0, slowMoMillis));
            Browser runBrowser = runPlaywright.chromium().launch(options);
            BrowserContext runContext = runBrowser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 800));
            Page runPage = runContext.newPage();
            synchronized (lock) {
                activeRunPlaywright = runPlaywright;
                activeRunBrowser = runBrowser;
                activeRunContext = runContext;
                activeRunPage = runPage;
                activeRunCloseBrowserOnStop = true;
            }

            Path screenshotDir = Path.of("target", "web-test-screenshots");
            Files.createDirectories(screenshotDir);

            for (WebTestStep step : testCase.steps) {
                if (webTestStopRequested) {
                    report.stopped = true;
                    break;
                }
                WebTestExecutionResult result = executeStep(runPage, step, screenshotDir, report);
                report.results.add(result);
                if (webTestStopRequested) {
                    report.stopped = true;
                    break;
                }
            }

            closeQuietly(runContext);
            closeQuietly(runBrowser);
        } finally {
            synchronized (lock) {
                activeRunPage = null;
                activeRunContext = null;
                activeRunBrowser = null;
                activeRunPlaywright = null;
                activeRunCloseBrowserOnStop = false;
            }
        }

        report.total = report.results.size();
        report.passed = (int) report.results.stream().filter(result -> result.passed).count();
        report.failed = report.total - report.passed;
        report.totalDurationMs = System.currentTimeMillis() - suiteStart;
        return report;
    }

    public void stopRunningWebTest() {
        webTestStopRequested = true;
        synchronized (lock) {
            if (activeRunCloseBrowserOnStop) {
                closeQuietly(activeRunPage);
                closeQuietly(activeRunContext);
                closeQuietly(activeRunBrowser);
            } else {
                closeQuietly(activeRunPlaywright);
            }
        }
    }

    public WebTestRunReport retestStepsOnActiveBrowser(String cdpEndpoint, List<WebTestStep> steps,
                                                       int slowMoMillis) throws Exception {
        if (cdpEndpoint == null || cdpEndpoint.isBlank()) {
            throw new IllegalArgumentException("CDP endpoint is required. Example: http://localhost:9222");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Select at least one step to re-test.");
        }

        WebTestRunReport report = new WebTestRunReport();
        long suiteStart = System.currentTimeMillis();
        webTestStopRequested = false;

        Playwright runPlaywright = Playwright.create();
        Browser runBrowser = null;
        BrowserContext runContext = null;
        Page runPage = null;
        try {
            runBrowser = connectOverCdpWithFallback(runPlaywright, cdpEndpoint.trim());
            runContext = runBrowser.contexts().isEmpty() ? runBrowser.newContext() : runBrowser.contexts().get(0);
            List<Page> pages = runContext.pages();
            runPage = pages.isEmpty() ? runContext.newPage() : pages.get(pages.size() - 1);
            synchronized (lock) {
                activeRunPlaywright = runPlaywright;
                activeRunBrowser = runBrowser;
                activeRunContext = runContext;
                activeRunPage = runPage;
                activeRunCloseBrowserOnStop = false;
            }

            Path screenshotDir = Path.of("target", "web-test-screenshots");
            Files.createDirectories(screenshotDir);

            for (WebTestStep step : steps) {
                if (webTestStopRequested) {
                    report.stopped = true;
                    break;
                }
                WebTestExecutionResult result = executeStep(runPage, step, screenshotDir, report);
                report.results.add(result);
                if (slowMoMillis > 0) {
                    Thread.sleep(slowMoMillis);
                }
                if (webTestStopRequested) {
                    report.stopped = true;
                    break;
                }
            }
        } finally {
            synchronized (lock) {
                activeRunPage = null;
                activeRunContext = null;
                activeRunBrowser = null;
                activeRunPlaywright = null;
                activeRunCloseBrowserOnStop = false;
            }
            closeQuietly(runPlaywright);
        }

        report.total = report.results.size();
        report.passed = (int) report.results.stream().filter(result -> result.passed).count();
        report.failed = report.total - report.passed;
        report.totalDurationMs = System.currentTimeMillis() - suiteStart;
        return report;
    }

    public List<WebTestStep> snapshotCapturedSteps() {
        return new ArrayList<>(capturedSteps);
    }

    private void registerPage(Page pageToRegister) {
        pageToRegister.onLoad(page -> handlePageLoaded(pageToRegister));
        pageToRegister.onClose(page -> emitStatus("Recording browser page closed."));
    }

    private void installRecorderOnPage(Page targetPage) {
        try {
            targetPage.evaluate(RECORDER_SCRIPT);
        } catch (PlaywrightException ignored) {
            // ignore pages that are closed, navigating, or browser-internal
        }
    }

    private void startEventPolling() {
        stopEventPolling();
        eventPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "testweave-web-recorder-poller");
            thread.setDaemon(true);
            return thread;
        });
        eventPoller.scheduleAtFixedRate(this::pollRecordedEvents, 250, 250, TimeUnit.MILLISECONDS);
    }

    private void stopEventPolling() {
        if (eventPoller != null) {
            eventPoller.shutdownNow();
            eventPoller = null;
        }
    }

    private void pollRecordedEvents() {
        if (!recording || context == null) {
            return;
        }
        try {
            for (Page openPage : context.pages()) {
                Object events = openPage.evaluate("""
                        () => {
                          const events = Array.isArray(window.__apiValidatorRecordedEvents)
                            ? window.__apiValidatorRecordedEvents.splice(0)
                            : [];
                          return events.map(event => typeof event === 'string' ? event : JSON.stringify(event));
                        }
                        """);
                if (events instanceof List<?> eventList) {
                    for (Object event : eventList) {
                        if (event != null) {
                            handleBrowserPayload(String.valueOf(event));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Pages can navigate or close while polling; the next tick can continue.
        }
    }

    private void handleBrowserPayload(String payload) {
        if (!recording) {
            return;
        }
        try {
            JSONObject json = new JSONObject(payload);
            WebTestStep step = new WebTestStep();
            step.action = json.optString("action");
            step.selector = json.optString("selector");
            step.value = json.optString("value");
            step.note = json.optString("note");
            step.suggested = false;

            if ("Click".equalsIgnoreCase(step.action)) {
                pendingNavigationSource = step.selector;
            }

            appendCapturedStep(step);
        } catch (Exception e) {
            emitError("Failed to capture browser action: " + e.getMessage());
        }
    }

    private void handlePageLoaded(Page loadedPage) {
        if (!recording) {
            return;
        }

        installRecorderOnPage(loadedPage);

        String currentUrl = loadedPage.url();
        emitUrlChanged(currentUrl);
        if (currentUrl != null && !currentUrl.equals(lastRecordedUrl)) {
            WebTestStep navigateStep = new WebTestStep();
            navigateStep.action = "Navigate";
            navigateStep.selector = "";
            navigateStep.value = currentUrl;
            navigateStep.note = pendingNavigationSource == null ? "page loaded" : "after " + pendingNavigationSource;
            navigateStep.suggested = false;
            appendCapturedStep(navigateStep);
            lastRecordedUrl = currentUrl;
            pendingNavigationSource = null;
            maybeSuggestValidation(loadedPage);
        }
    }

    private void maybeSuggestValidation(Page activePage) {
        try {
            Object candidate = activePage.evaluate("""
                    () => {
                      const nodes = Array.from(document.querySelectorAll('h1, h2, [role="heading"], [data-testid], .welcome, .title'))
                        .filter(node => node instanceof HTMLElement && (node.innerText || '').trim().length > 0);
                      if (nodes.length === 0) {
                        return null;
                      }
                      const node = nodes[0];
                      const text = (node.innerText || '').trim().replace(/\\s+/g, ' ');
                      if (!text) {
                        return null;
                      }
                      const selector = node.getAttribute('data-testid')
                        ? `[data-testid="${node.getAttribute('data-testid')}"]`
                        : node.id
                          ? `#${node.id}`
                          : node.tagName.toLowerCase();
                      return { selector, text };
                    }
                    """);
            if (candidate instanceof java.util.Map<?, ?> map) {
                Object selector = map.get("selector");
                Object text = map.get("text");
                if (selector != null && text != null) {
                    WebTestStep step = new WebTestStep();
                    step.action = "Validate Text";
                    step.selector = String.valueOf(selector);
                    step.value = String.valueOf(text);
                    step.note = "Suggested after navigation";
                    step.suggested = true;
                    appendCapturedStep(step);
                }
            }
        } catch (Exception ignored) {
            // suggestion should never block recording
        }
    }

    private void appendCapturedStep(WebTestStep step) {
        if (step == null || step.action == null || step.action.isBlank()) {
            return;
        }
        if (isDuplicate(step)) {
            return;
        }
        capturedSteps.add(step);
        if (listener != null) {
            listener.onStepCaptured(step);
        }
    }

    private boolean isDuplicate(WebTestStep step) {
        if (capturedSteps.isEmpty()) {
            return false;
        }
        WebTestStep lastStep = capturedSteps.get(capturedSteps.size() - 1);
        return safe(lastStep.action).equals(safe(step.action))
                && safe(lastStep.selector).equals(safe(step.selector))
                && safe(lastStep.value).equals(safe(step.value))
                && safe(lastStep.note).equals(safe(step.note));
    }

    private WebTestExecutionResult executeStep(Page runPage, WebTestStep step, Path screenshotDir, WebTestRunReport report) {
        WebTestExecutionResult result = new WebTestExecutionResult();
        result.action = step.action;
        result.selector = step.selector;
        result.expectedValue = step.value;

        long start = System.currentTimeMillis();
        try {
            switch (safe(step.action).toLowerCase()) {
                case "navigate":
                    runPage.navigate(step.value);
                    result.message = "Page loaded successfully";
                    break;
                case "type":
                    resolveLocator(runPage, step.selector).fill(step.value == null ? "" : step.value);
                    result.message = "Text entered";
                    break;
                case "click":
                    if (isRecordedOptionClick(step)) {
                        resolveLocator(runPage, parentSelectSelector(step.selector)).selectOption(step.value);
                        result.message = "Dropdown option selected";
                        break;
                    }
                    resolveLocator(runPage, step.selector).click();
                    result.message = "Element clicked";
                    break;
                case "select option":
                    resolveLocator(runPage, step.selector).selectOption(step.value == null ? "" : step.value);
                    result.message = "Dropdown option selected";
                    break;
                case "validate text":
                    String actualText = resolveLocator(runPage, step.selector).innerText();
                    if (actualText == null || !actualText.contains(step.value == null ? "" : step.value)) {
                        throw new IllegalStateException("Expected text '" + step.value
                                + "' was not found. Actual text: '" + actualText + "'.");
                    }
                    result.message = "Text matched";
                    break;
                case "get text":
                    String variableName = normalizeVariableName(step.value);
                    if (variableName.isBlank()) {
                        throw new IllegalArgumentException("Enter a variable name in Value / Expected for Get Text.");
                    }
                    String extractedText = resolveLocator(runPage, step.selector).innerText();
                    result.capturedVariableName = variableName;
                    result.capturedVariableValue = extractedText == null ? "" : extractedText.trim();
                    result.expectedValue = variableName;
                    result.message = "Saved text to ${" + variableName + "}";
                    break;
                case "screenshot":
                    String baseName = safe(step.value).isBlank() ? "screenshot-" + Instant.now().toEpochMilli() + ".png" : step.value;
                    if (!baseName.toLowerCase().endsWith(".png")) {
                        baseName = baseName + ".png";
                    }
                    Path screenshotPath = screenshotDir.resolve(baseName);
                    runPage.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));
                    report.lastScreenshotPath = screenshotPath;
                    result.message = "Screenshot captured";
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported web action: " + step.action);
            }
            result.passed = true;
        } catch (Exception e) {
            result.passed = false;
            result.message = enrichStepFailureMessage(runPage, step,
                    e.getMessage() == null ? "Step failed." : e.getMessage());
        }
        result.durationMs = System.currentTimeMillis() - start;
        return result;
    }

    private String enrichStepFailureMessage(Page runPage, WebTestStep step, String baseMessage) {
        String selector = safe(step.selector);
        if (runPage == null || selector.isBlank() || "navigate".equalsIgnoreCase(safe(step.action))) {
            return baseMessage;
        }
        try {
            com.microsoft.playwright.Locator locator = resolveLocator(runPage, selector);
            int count = locator.count();
            if (count == 0) {
                return baseMessage + " | Actual on screen for selector: no matching element found.";
            }

            com.microsoft.playwright.Locator first = locator.first();
            String text = safe(first.textContent());
            String value = "";
            try {
                value = safe(first.inputValue());
            } catch (Exception ignored) {
                // Non-input elements do not expose inputValue.
            }
            boolean visible = false;
            try {
                visible = first.isVisible();
            } catch (Exception ignored) {
                // Visibility checks can fail while pages are changing.
            }

            StringBuilder details = new StringBuilder(baseMessage)
                    .append(" | Actual on screen for selector: matches=")
                    .append(count)
                    .append(", visible=")
                    .append(visible);
            if (!text.isBlank()) {
                details.append(", text='").append(truncateForMessage(text)).append("'");
            }
            if (!value.isBlank()) {
                details.append(", value='").append(truncateForMessage(value)).append("'");
            }
            if (text.isBlank() && value.isBlank()) {
                details.append(", text/value empty");
            }
            return details.toString();
        } catch (Exception ignored) {
            return baseMessage;
        }
    }

    private com.microsoft.playwright.Locator resolveLocator(Page page, String selector) {
        String normalized = safe(selector);
        if (normalized.isBlank()) {
            return page.locator(normalized);
        }

        String lower = normalized.toLowerCase();
        if (lower.startsWith("xpath=") || lower.startsWith("css=") || lower.startsWith("text=")) {
            return page.locator(normalized);
        }
        if (lower.startsWith("id=")) {
            return page.locator("#" + cssEscape(normalized.substring(3)));
        }
        if (lower.startsWith("name=")) {
            return page.locator("[name=\"" + cssAttributeEscape(normalized.substring(5)) + "\"]");
        }
        if (lower.startsWith("class=") || lower.startsWith("classname=")) {
            String className = normalized.substring(normalized.indexOf('=') + 1).trim();
            return page.locator("." + cssEscape(className));
        }

        if (looksLikeXPath(normalized)) {
            return page.locator("xpath=" + normalized);
        }

        List<String> selectorCandidates = new ArrayList<>();
        selectorCandidates.add(normalized);
        if (normalized.startsWith("#") || normalized.startsWith(".") || normalized.startsWith("[")
                || normalized.contains(" ") || normalized.contains(">") || normalized.contains(":")) {
            selectorCandidates.add("text=" + normalized);
        } else {
            selectorCandidates.add("#" + cssEscape(normalized));
            selectorCandidates.add("[name=\"" + cssAttributeEscape(normalized) + "\"]");
            selectorCandidates.add("." + cssEscape(normalized));
            selectorCandidates.add("text=" + normalized);
        }

        com.microsoft.playwright.Locator fallback = null;
        for (String candidate : selectorCandidates) {
            try {
                com.microsoft.playwright.Locator locator = page.locator(candidate);
                if (fallback == null) {
                    fallback = locator;
                }
                if (locator.count() > 0) {
                    return locator;
                }
            } catch (Exception ignored) {
                // Try the next selector form when the current one is not valid for its engine.
            }
        }
        return fallback == null ? page.locator(normalized) : fallback;
    }

    private boolean looksLikeXPath(String selector) {
        String trimmed = safe(selector);
        return trimmed.startsWith("/")
                || trimmed.startsWith("(")
                || trimmed.startsWith("./")
                || trimmed.startsWith("../")
                || trimmed.startsWith("//")
                || trimmed.startsWith(".//");
    }

    private String normalizeVariableName(String variableName) {
        String normalized = safe(variableName).trim();
        if (normalized.startsWith("${") && normalized.endsWith("}") && normalized.length() > 3) {
            normalized = normalized.substring(2, normalized.length() - 1).trim();
        }
        return normalized.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String cssEscape(String value) {
        return safe(value).replace("\\", "\\\\")
                .replace(".", "\\.")
                .replace("#", "\\#")
                .replace(":", "\\:")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace(" ", "\\ ");
    }

    private String cssAttributeEscape(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String truncateForMessage(String value) {
        String normalized = safe(value).replaceAll("\\s+", " ");
        return normalized.length() > 240 ? normalized.substring(0, 237) + "..." : normalized;
    }

    private boolean isRecordedOptionClick(WebTestStep step) {
        String selector = safe(step.selector).toLowerCase();
        return !safe(step.value).isBlank()
                && (selector.contains("/option[") || selector.contains(" option") || selector.startsWith("option"));
    }

    private String parentSelectSelector(String optionSelector) {
        String selector = safe(optionSelector);
        int xpathOptionIndex = selector.toLowerCase().lastIndexOf("/option[");
        if (xpathOptionIndex > 0) {
            return selector.substring(0, xpathOptionIndex);
        }
        int cssOptionIndex = selector.toLowerCase().lastIndexOf(" option");
        if (cssOptionIndex > 0) {
            return selector.substring(0, cssOptionIndex);
        }
        return selector;
    }

    private void stopRecordingInternal(boolean notifyListener, boolean closeBrowser) {
        recording = false;
        stopEventPolling();
        if (closeBrowser) {
            closeQuietly(page);
            closeQuietly(context);
            closeQuietly(browser);
        }
        if (closeBrowser || attachedRecording) {
            closeQuietly(playwright);
            page = null;
            context = null;
            browser = null;
            playwright = null;
            attachedRecording = false;
        }
        lastRecordedUrl = null;
        pendingNavigationSource = null;
        if (notifyListener && listener != null) {
            listener.onRecordingStopped();
        }
        listener = null;
    }

    private void closeQuietly(Page resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void closeQuietly(BrowserContext resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void closeQuietly(Browser resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void closeQuietly(Playwright resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void emitStatus(String message) {
        if (listener != null) {
            listener.onStatus(message);
        }
    }

    private void emitUrlChanged(String url) {
        if (listener != null) {
            listener.onUrlChanged(url);
        }
    }

    private void emitError(String message) {
        if (listener != null) {
            listener.onError(message);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
