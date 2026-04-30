package demo.ce316.terminal;

import javafx.scene.web.WebEngine;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * Terminal backend for Windows.
 *
 * <p>Starts a persistent {@code cmd.exe} process with:
 * <ul>
 *   <li>{@code /Q}    — quiet mode (suppresses command echo)</li>
 *   <li>{@code /V:ON} — enables delayed variable expansion ({@code !VAR!})
 *                       so we can safely capture {@code ERRORLEVEL} after
 *                       each user command</li>
 *   <li>{@code /K}    — keep-alive (do not exit after first command)</li>
 * </ul>
 *
 * <p>Sentinel lines use {@code !CD!} for the working directory and a
 * saved {@code !__r!} variable for the exit code — both evaluated at
 * execution time thanks to delayed expansion.
 *
 * <p>The cmd.exe startup banner ("Microsoft Windows [Version …]") is
 * filtered out in the shared reader thread by {@link #isBannerLine(String)}.
 */
public final class WindowsTerminalBackend extends AbstractTerminalBackend {

    public WindowsTerminalBackend(WebEngine webEngine) {
        super(webEngine);
        startShell();
    }

    // ── Shell lifecycle ───────────────────────────────────────────────────────

    @Override
    protected void startShell() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "cmd.exe", "/Q", "/V:ON", "/K", "@echo off");
            pb.environment().put("PROMPT", "");     // suppress the C:\> prompt
            pb.directory(new File(System.getProperty("user.home")));
            pb.redirectErrorStream(true);

            process = pb.start();
            stdin   = new PrintWriter(
                new OutputStreamWriter(process.getOutputStream()), true);

            startReader();

            // Initial sentinel — delayed-expansion syntax for cmd
            stdin.println("echo " + SEN_CWD + "!CD!& echo " + SEN_DONE + "0");
            stdin.flush();

        } catch (Exception e) {
            System.err.println("WindowsTerminalBackend: startShell failed — " + e.getMessage());
        }
    }

    // ── TerminalBackend ───────────────────────────────────────────────────────

    @Override
    public void send(String cmd) {
        if (!isAlive()) startShell();
        stdin.println(cmd);
        // Save ERRORLEVEL immediately (it resets on the next internal command),
        // then emit the two sentinel lines on a single logical line.
        stdin.println(
            "set \"__r=!ERRORLEVEL!\"& " +
            "echo " + SEN_CWD  + "!CD!& " +
            "echo " + SEN_DONE + "!__r!");
        stdin.flush();
    }

    @Override
    public void interrupt() {
        if (isAlive()) {
            // Send Ctrl+C byte followed by a newline so cmd processes it
            stdin.print('');   // ETX = Ctrl+C
            stdin.println();
            stdin.flush();
        }
    }

    // ── Overridden reader to filter Windows-specific noise ───────────────────

    /**
     * Wraps the base {@link #startReader()} with Windows-specific line
     * filtering.  We override the output-line handling by subclassing the
     * reader thread indirectly: the shared reader already calls
     * {@link #startReader()}, so we intercept at the sentinel-parsing level
     * by overriding the inherited {@code startReader} via a filtering wrapper.
     *
     * <p>Because we cannot easily override the private lambda inside
     * {@link AbstractTerminalBackend#startReader()}, we instead spawn our own
     * filtered reader thread and do NOT call {@code super.startReader()}.
     */
    @Override
    protected void startReader() {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Skip cmd.exe startup banner and blank lines
                    if (isBannerLine(line) || line.trim().isEmpty()) continue;

                    // Strip any accidental ANSI codes (Windows 10+ VT mode)
                    String clean = line.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "")
                                       .replaceAll("\\[[0-9;]*[A-Za-z]", "")
                                       .replace("\r", "");

                    if (clean.startsWith(SEN_CWD)) {
                        final String cwd = clean.substring(SEN_CWD.length()).trim();
                        javafx.application.Platform.runLater(() ->
                            jsCall("terminalUpdateCwd('" + escJs(cwd) + "')"));

                    } else if (clean.startsWith(SEN_DONE)) {
                        String rcRaw = clean.substring(SEN_DONE.length())
                                            .replaceAll("[^0-9]", "");
                        final String rc = rcRaw.isEmpty() ? "0" : rcRaw;
                        javafx.application.Platform.runLater(() ->
                            jsCall("terminalCommandDone(" + rc + ")"));

                    } else if (!clean.isEmpty()) {
                        final String escaped = escJs(clean);
                        javafx.application.Platform.runLater(() ->
                            jsCall("terminalReceiveLine('" + escaped + "')"));
                    }
                }
            } catch (Exception ignored) {}

            javafx.application.Platform.runLater(() -> jsCall(
                "terminalReceiveLine('[shell process ended]');" +
                "terminalCommandDone(0)"));

        }, "iae-terminal-reader-win");
        t.setDaemon(true);
        t.start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} for lines that are part of the cmd.exe startup
     * banner and should never be shown to the user.
     */
    private static boolean isBannerLine(String line) {
        String t = line.trim();
        return t.startsWith("Microsoft Windows")
            || t.startsWith("(c) Microsoft")
            || t.startsWith("(C) Microsoft");
    }
}
