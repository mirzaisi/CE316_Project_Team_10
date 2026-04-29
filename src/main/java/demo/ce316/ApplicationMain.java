package demo.ce316;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;
import javafx.stage.Stage;
import javafx.scene.text.Font;
import netscape.javascript.JSObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

public class ApplicationMain extends Application {

    // Stored as fields so inner classes and TerminalSession can reference them
    private WebEngine webEngine;
    private TerminalSession terminalSession;
    private final JavaBridge bridge = new JavaBridge();

    @Override
    public void start(Stage stage) {
        Font.loadFont(getClass().getResourceAsStream("MaterialSymbols.ttf"),14);
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

        /** Sends a command to the persistent shell session. Output comes back via JS callbacks. */
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


        public String getInstalledApps() {
            List<File> bundles = new ArrayList<>();
            collectAppBundles(new File("/Applications"), bundles, 2);
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

                String iconUrl  = (cached.exists() && cached.length() > 0)
                    ? cached.toURI().toString() : "";

                if (iconUrl.isEmpty()) toExtract.add(new Object[]{app, name, cached});

                if (!first) sb.append(",");
                sb.append("{\"id\":");   appendJsonStr(sb, name);
                sb.append(",\"name\":"); appendJsonStr(sb, name);
                sb.append(",\"iconUrl\":"); appendJsonStr(sb, iconUrl);
                sb.append("}");
                first = false;
            }

            // Background extraction for uncached icons (daemon threads)
            if (!toExtract.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(4, toExtract.size()), r -> {
                        Thread t = new Thread(r, "iae-icon-extractor");
                        t.setDaemon(true);
                        return t;
                    });
                for (Object[] item : toExtract) {
                    pool.submit(() -> extractIcon((File) item[0], (String) item[1], (File) item[2]));
                }
                pool.shutdown();
            }

            return sb.append("]").toString();
        }

        public String getInstalledLanguages() {
            // {id, displayName, command, versionFlag}
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

            ExecutorService pool = Executors.newFixedThreadPool(defs.length, r -> {
                Thread t = new Thread(r, "iae-lang-detect");
                t.setDaemon(true);
                return t;
            });

            List<Future<String[]>> futures = new ArrayList<>();
            for (String[] d : defs) {
                final String[] def = d;
                futures.add(pool.submit(() -> {
                    String ver = detectTool(def[2], def[3]);
                    return ver != null ? new String[]{def[0], def[1], ver} : null;
                }));
            }
            pool.shutdown();
            try { pool.awaitTermination(6, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Future<String[]> f : futures) {
                try {
                    String[] r = f.isDone() ? f.get() : null;
                    if (r == null) continue;
                    if (!first) sb.append(",");
                    sb.append("{\"id\":");      appendJsonStr(sb, r[0]);
                    sb.append(",\"name\":");    appendJsonStr(sb, r[1]);
                    sb.append(",\"version\":"); appendJsonStr(sb, r[2]);
                    sb.append("}");
                    first = false;
                } catch (Exception ignored) {}
            }
            return sb.append("]").toString();
        }

        // ── private helpers ──────────────────────────────────────────────

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

                String out = new String(bytes).trim();
                if (out.isEmpty()) return null;

                String line = out.split("\\n")[0].trim();
                java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(line);
                return m.find() ? m.group(1) : (line.length() > 50 ? line.substring(0, 50) : line);
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

    // ─────────────────────────────────────────────────────────────────────────
    //  TerminalSession — persistent /bin/bash process with bidirectional I/O
    // ─────────────────────────────────────────────────────────────────────────
    private static class TerminalSession {

        private static final String SHELL_PATH =
            "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin:/opt/homebrew/sbin";

        // Sentinels that are extremely unlikely to appear in normal command output
        private static final String SEN_CWD  = "__IAE_CWD_7f3a__:";
        private static final String SEN_DONE = "__IAE_DONE_7f3a__:";

        private Process    process;
        private PrintWriter stdin;
        private final WebEngine webEngine;

        TerminalSession(WebEngine we) {
            this.webEngine = we;
            startShell();
        }

        // ── Shell lifecycle ───────────────────────────────────────────────

        private void startShell() {
            try {
                ProcessBuilder pb = new ProcessBuilder("/bin/bash", "--norc", "--noprofile");
                pb.environment().put("TERM",     "dumb");
                pb.environment().put("PS1",      "");
                pb.environment().put("HISTFILE", "");
                String envPath = System.getenv("PATH");
                pb.environment().put("PATH",
                    SHELL_PATH + (envPath != null ? ":" + envPath : ""));
                pb.directory(new java.io.File(System.getProperty("user.home")));
                pb.redirectErrorStream(true);

                process = pb.start();
                stdin   = new PrintWriter(
                    new java.io.OutputStreamWriter(process.getOutputStream()), true);

                startReader();

                // Emit initial CWD/DONE so the JS prompt initialises correctly
                stdin.println("echo '" + SEN_CWD  + "'\"$(pwd)\"");
                stdin.println("echo '" + SEN_DONE + "0'");
                stdin.flush();

            } catch (Exception e) {
                System.err.println("TerminalSession: start failed — " + e.getMessage());
            }
        }

        private void startReader() {
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        // Strip ANSI escape codes
                        String clean = line.replaceAll("\\[[0-9;]*[A-Za-z]", "");

                        if (clean.startsWith(SEN_CWD)) {
                            final String cwd = clean.substring(SEN_CWD.length());
                            Platform.runLater(() -> jsCall(
                                "terminalUpdateCwd('" + escJs(cwd) + "')"));

                        } else if (clean.startsWith(SEN_DONE)) {
                            String rcRaw = clean.substring(SEN_DONE.length())
                                               .replaceAll("[^0-9]", "");
                            final String rc = rcRaw.isEmpty() ? "0" : rcRaw;
                            Platform.runLater(() -> jsCall(
                                "terminalCommandDone(" + rc + ")"));

                        } else if (!clean.isEmpty()) {
                            final String esc = escJs(clean);
                            Platform.runLater(() -> jsCall(
                                "terminalReceiveLine('" + esc + "')"));
                        }
                    }
                } catch (Exception ignored) {}

                // Shell exited — notify JS
                Platform.runLater(() -> jsCall(
                    "terminalReceiveLine('[shell process ended]');" +
                    "terminalCommandDone(0)"));
            }, "iae-terminal-reader");
            t.setDaemon(true);
            t.start();
        }

        // ── Public API ────────────────────────────────────────────────────

        void send(String cmd) {
            if (process == null || !process.isAlive()) startShell();
            stdin.println(cmd);
            // Capture exit code first, then report CWD + done
            stdin.println("__iae_rc=$?; echo '" + SEN_CWD  + "'\"$(pwd)\"; " +
                                        "echo '" + SEN_DONE + "'\"$__iae_rc\"");
            stdin.flush();
        }

        void interrupt() {
            if (process != null && process.isAlive()) {
                stdin.print('\u0003'); // ETX = Ctrl+C
                stdin.println();
                stdin.flush();
            }
        }

        void destroy() {
            if (process != null) process.destroyForcibly();
        }

        // ── Helpers ───────────────────────────────────────────────────────

        private void jsCall(String expr) {
            try {
                webEngine.executeScript("if(typeof " + expr.split("\\(")[0] +
                    "==='function'){" + expr + "}");
            } catch (Exception e) {
                System.err.println("TerminalSession jsCall error: " + e.getMessage());
            }
        }

        private static String escJs(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("'",  "\\'")
                    .replace("\r", "");
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}