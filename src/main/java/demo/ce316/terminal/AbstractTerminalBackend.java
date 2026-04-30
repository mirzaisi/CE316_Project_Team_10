package demo.ce316.terminal;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;

/**
 * Shared infrastructure for all terminal backends.
 *
 * <p>Subclasses only need to implement {@link #startShell()} — everything
 * else (stdout reader thread, sentinel parsing, JS callbacks, helpers) lives
 * here so it is not duplicated across OS implementations.
 *
 * <p>Sentinel protocol:
 * <ul>
 *   <li>{@code __IAE_CWD_7f3a__:<path>}  → JS {@code terminalUpdateCwd(path)}</li>
 *   <li>{@code __IAE_DONE_7f3a__:<rc>}   → JS {@code terminalCommandDone(rc)}</li>
 *   <li>any other non-empty line          → JS {@code terminalReceiveLine(line)}</li>
 * </ul>
 */
public abstract class AbstractTerminalBackend implements TerminalBackend {

    // ── Sentinel strings ─────────────────────────────────────────────────────
    protected static final String SEN_CWD  = "__IAE_CWD_7f3a__:";
    protected static final String SEN_DONE = "__IAE_DONE_7f3a__:";

    // ── Shared state (set by subclass inside startShell) ─────────────────────
    protected Process     process;
    protected PrintWriter stdin;

    protected final WebEngine webEngine;

    protected AbstractTerminalBackend(WebEngine webEngine) {
        this.webEngine = webEngine;
    }

    // ── Abstract hook ────────────────────────────────────────────────────────

    /**
     * Start (or restart) the shell process. Implementations must:
     * <ol>
     *   <li>Build and start a {@link ProcessBuilder}.</li>
     *   <li>Assign {@link #process} and {@link #stdin}.</li>
     *   <li>Call {@link #startReader()}.</li>
     *   <li>Write the initial sentinel echo commands to stdin.</li>
     * </ol>
     */
    protected abstract void startShell();

    // ── TerminalBackend ───────────────────────────────────────────────────────

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public void destroy() {
        if (process != null) process.destroyForcibly();
    }

    // ── Shared reader thread ─────────────────────────────────────────────────

    /**
     * Spawn the background thread that reads lines from the shell's stdout,
     * parses sentinel prefixes, and dispatches JS callbacks via
     * {@link Platform#runLater}.
     *
     * Must be called once per {@link #startShell()} invocation.
     */
    protected void startReader() {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Strip ANSI / VT escape sequences
                    String clean = line.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "")
                                       .replaceAll("\\[[0-9;]*[A-Za-z]", "");

                    if (clean.startsWith(SEN_CWD)) {
                        final String cwd = clean.substring(SEN_CWD.length()).trim();
                        Platform.runLater(() ->
                            jsCall("terminalUpdateCwd('" + escJs(cwd) + "')"));

                    } else if (clean.startsWith(SEN_DONE)) {
                        String rcRaw = clean.substring(SEN_DONE.length())
                                            .replaceAll("[^0-9]", "");
                        final String rc = rcRaw.isEmpty() ? "0" : rcRaw;
                        Platform.runLater(() ->
                            jsCall("terminalCommandDone(" + rc + ")"));

                    } else if (!clean.isEmpty()) {
                        final String escaped = escJs(clean);
                        Platform.runLater(() ->
                            jsCall("terminalReceiveLine('" + escaped + "')"));
                    }
                }
            } catch (Exception ignored) {}

            // Shell process ended — let JS know
            Platform.runLater(() -> jsCall(
                "terminalReceiveLine('[shell process ended]');" +
                "terminalCommandDone(0)"));

        }, "iae-terminal-reader");
        t.setDaemon(true);
        t.start();
    }

    // ── JS bridge helpers ────────────────────────────────────────────────────

    protected void jsCall(String expr) {
        try {
            String fnName = expr.split("\\(")[0];
            webEngine.executeScript(
                "if(typeof " + fnName + "==='function'){" + expr + "}");
        } catch (Exception e) {
            System.err.println("TerminalBackend jsCall error: " + e.getMessage());
        }
    }

    protected static String escJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'",  "\\'")
                .replace("\r", "");
    }
}
