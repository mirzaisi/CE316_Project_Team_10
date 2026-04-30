package demo.ce316;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;
import javafx.stage.Stage;
import javafx.scene.text.Font;
import netscape.javascript.JSObject;

import demo.ce316.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ApplicationMain extends Application {

    // Stored as fields so inner classes and TerminalSession can reference them
    private WebEngine webEngine;
    private TerminalSession terminalSession;
    private final JavaBridge bridge = new JavaBridge();

    @Override
    public void start(Stage stage) {
        Font.loadFont(getClass().getResourceAsStream("fonts/MaterialSymbols.ttf"), 14);
        WebView webView = new WebView();
        webEngine = webView.getEngine();

        var resource = getClass().getResource("index.html");
        if (resource == null) {
            System.err.println("Error: Resource not found!\nindex.html not found!");
        } else {
            webEngine.load(resource.toExternalForm());
        }

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) webEngine.executeScript("window");
                window.setMember("app", bridge);
                webEngine.executeScript(
                    "(function retry() {" +
                    "  if (typeof loadDashboard === 'function') { loadDashboard(); }" +
                    "  else { setTimeout(retry, 100); }" +
                    "})();"
                );
            }
        });

        Scene scene = new Scene(webView, 1280, 800);
        stage.minHeightProperty().set(800);
        stage.minWidthProperty().set(1280);
        stage.setTitle("IAE - Integrated Assignment Environment");
        stage.setScene(scene);
        stage.show();
    }

    public class JavaBridge {
        private final DatabaseManager db = DatabaseManager.getInstance();

        public String getProjects() {
            return db.getProjectsJson();
        }

        public String getStudents(String projectId) {
            return db.getStudentsJson(projectId);
        }

        public String getLogs(String projectId) {
            return db.getLogsJson(projectId);
        }

        public String getConfig(String projectId) {
            return db.getConfigJson(projectId);
        }

        public String getStudentCode(String studentId, String projectId) {
            return db.getStudentCodeJson(studentId, projectId);
        }

        public void saveConfig(String projectId, String lang, String timeout, String maxGrade, String flags) {
            db.saveConfig(projectId, lang,
                parseInt(timeout, 5),
                parseInt(maxGrade, 100),
                flags);
        }

        public void updateGrade(String studentId, String projectId, String grade) {
            db.updateGrade(studentId, projectId, parseInt(grade, 0));
        }

        public void runPipeline() {
            System.out.println("Java: Pipeline starting...");
        }

        /** Sends a command to the OS-appropriate persistent shell. Output comes back via JS callbacks. */
        public void runCommand(String cmd) {
            if (terminalSession == null) {
                terminalSession = new TerminalSession(webEngine);
            }
            terminalSession.send(cmd);
        }

        /** Sends Ctrl+C to the running process (interrupts blocking commands). */
        public void terminalInterrupt() {
            if (terminalSession != null) terminalSession.interrupt();
        }

        public void navigate(String page) {
            System.out.println("Navigate: " + page);
        }

        /**
         * Starts a fully asynchronous environment scan.
         * Returns immediately — JS is notified via callbacks as results arrive:
         *   onEnvLangs(jsonArray)   — all detected language runtimes
         *   onEnvApps(jsonArray)    — all installed .app bundles
         *   onEnvScanDone()         — scan fully complete
         */
        public void startEnvironmentScan() {
            Thread t = new Thread(() -> {
                scanLanguages();
                scanApps();
                Platform.runLater(() -> jsEnvCall("onEnvScanDone()"));
            }, "iae-env-scan");
            t.setDaemon(true);
            t.start();
        }

        // ── Language scan ────────────────────────────────────────────────────

        private void scanLanguages() {
            final String[][] defs = {
                {"gcc",    "GCC",      "gcc",      "--version"},
                {"clang",  "Clang",    "clang",    "--version"},
                {"python", "Python 3", "python3",  "--version"},
                {"java",   "Java",     "java",     "--version"},
                {"nodejs", "Node.js",  "node",     "--version"},
                {"go",     "Go",       "go",       "version"  },
                {"rust",   "Rust",     "rustc",    "--version"},
                {"ruby",   "Ruby",     "ruby",     "--version"},
                {"php",    "PHP",      "php",      "--version"},
                {"swift",  "Swift",    "swift",    "--version"},
                {"kotlin", "Kotlin",   "kotlinc",  "-version" },
                {"mvn",    "Maven",    "mvn",      "--version"},
            };

            // Detect each tool in parallel; each resolved result is pushed
            // to JS immediately so the UI can show it without waiting for all.
            ExecutorService pool = Executors.newFixedThreadPool(defs.length, r -> {
                Thread th = new Thread(r, "iae-lang-detect");
                th.setDaemon(true);
                return th;
            });

            List<Future<?>> futures = new ArrayList<>();
            for (String[] def : defs) {
                futures.add(pool.submit(() -> {
                    String ver = detectTool(def[2], def[3]);
                    if (ver == null && "python3".equals(def[2]))
                        ver = detectTool("python", def[3]);
                    if (ver == null) return;

                    StringBuilder sb = new StringBuilder("[{\"id\":");
                    appendJsonStr(sb, def[0]);
                    sb.append(",\"name\":"); appendJsonStr(sb, def[1]);
                    sb.append(",\"version\":"); appendJsonStr(sb, ver);
                    sb.append("}]");
                    final String json = sb.toString();
                    Platform.runLater(() -> jsEnvCall("onEnvLangs(" + json + ")"));
                }));
            }
            pool.shutdown();
            try { pool.awaitTermination(6, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // ── App scan ─────────────────────────────────────────────────────────

        private void scanApps() {
            List<File> bundles = new ArrayList<>();
            // On Windows there is no /Applications — skip silently
            File appsDir = new File("/Applications");
            if (appsDir.isDirectory()) collectAppBundles(appsDir, bundles, 2);

            bundles.sort(Comparator.comparing(f -> f.getName().toLowerCase()));

            File cacheDir = new File(System.getProperty("user.home"), ".iae/icons");
            cacheDir.mkdirs();

            List<Object[]> toExtract = new ArrayList<>();
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;

            for (File app : bundles) {
                String name     = app.getName().replaceAll("\\.app$", "");
                String safeName = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
                File   cached   = new File(cacheDir, safeName + ".png");

                String iconUrl = (cached.exists() && cached.length() > 0)
                    ? cached.toURI().toString() : "";

                if (iconUrl.isEmpty()) toExtract.add(new Object[]{app, name, cached});

                if (!first) sb.append(",");
                sb.append("{\"id\":");      appendJsonStr(sb, name);
                sb.append(",\"name\":");    appendJsonStr(sb, name);
                sb.append(",\"iconUrl\":"); appendJsonStr(sb, iconUrl);
                sb.append("}");
                first = false;
            }

            final String appsJson = sb.append("]").toString();
            Platform.runLater(() -> jsEnvCall("onEnvApps(" + appsJson + ")"));

            // Extract uncached icons in the background after the list is sent
            if (!toExtract.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(4, toExtract.size()), r -> {
                        Thread th = new Thread(r, "iae-icon-extractor");
                        th.setDaemon(true);
                        return th;
                    });
                for (Object[] item : toExtract) {
                    pool.submit(() -> extractIcon((File) item[0], (String) item[1], (File) item[2]));
                }
                pool.shutdown();
            }
        }

        // ── JS callback helper ────────────────────────────────────────────────

        private void jsEnvCall(String expr) {
            try {
                String fn = expr.split("\\(")[0];
                webEngine.executeScript(
                    "if(typeof " + fn + "==='function'){" + expr + "}");
            } catch (Exception e) {
                System.err.println("jsEnvCall error: " + e.getMessage());
            }
        }

        // ── private helpers ──────────────────────────────────────────────────

        private static final String TOOL_PATH =
            "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin:/opt/homebrew/sbin";

        private String detectTool(String cmd, String versionFlag) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, versionFlag);
                pb.redirectErrorStream(true);
                String envPath = System.getenv("PATH");
                pb.environment().put("PATH",
                    TOOL_PATH + (envPath != null ? ":" + envPath : ""));

                Process proc = pb.start();
                byte[] bytes = proc.getInputStream().readAllBytes();
                boolean done = proc.waitFor(3, TimeUnit.SECONDS);
                if (!done) { proc.destroyForcibly(); return null; }

                // Non-zero exit → tool not installed or returned an error
                if (proc.exitValue() != 0) return null;

                String out = new String(bytes).trim();
                if (out.isEmpty()) return null;

                // Only return a result if we can extract an actual version number.
                // Never return raw error messages (e.g. Windows App Execution Aliases).
                String line = out.split("\\n")[0].trim();
                java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(line);
                return m.find() ? m.group(1) : null;
            } catch (Exception e) {
                return null;
            }
        }

        private void collectAppBundles(File dir, List<File> result, int depth) {
            if (depth <= 0 || !dir.isDirectory()) return;
            File[] children = dir.listFiles();
            if (children == null) return;
            for (File f : children) {
                if (f.isDirectory() && f.getName().endsWith(".app")) {
                    result.add(f);
                } else if (f.isDirectory() && depth > 1) {
                    collectAppBundles(f, result, depth - 1);
                }
            }
        }

        private void extractIcon(File appBundle, String appName, File cachedPng) {
            File icns = findIcnsFile(appBundle);
            if (icns == null) return;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "sips", "-s", "format", "png",
                    icns.getAbsolutePath(),
                    "--out", cachedPng.getAbsolutePath(),
                    "-Z", "128"
                );
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                proc.waitFor();
            } catch (Exception e) {
                System.err.println("Icon extraction failed for " + appName + ": " + e.getMessage());
            }
        }

        private File findIcnsFile(File appBundle) {
            File resources = new File(appBundle, "Contents/Resources");
            if (!resources.isDirectory()) return null;
            File appIcon = new File(resources, "AppIcon.icns");
            if (appIcon.exists()) return appIcon;
            File[] files = resources.listFiles();
            if (files == null) return null;
            for (File f : files) {
                if (f.getName().endsWith(".icns")) return f;
            }
            return null;
        }

        private void appendJsonStr(StringBuilder sb, String s) {
            sb.append('"');
            if (s != null) {
                for (char c : s.toCharArray()) {
                    switch (c) {
                        case '"'  -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        case '\r' -> sb.append("\\r");
                        case '\t' -> sb.append("\\t");
                        default   -> { if (c >= 0x20) sb.append(c); }
                    }
                }
            }
            sb.append('"');
        }

        private int parseInt(String s, int fallback) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) { return fallback; }
        }
    }

    @Override
    public void stop() {
        DatabaseManager.getInstance().close();
        if (terminalSession != null) terminalSession.destroy();
    }

    public static void main(String[] args) {
        launch(args);
    }
}