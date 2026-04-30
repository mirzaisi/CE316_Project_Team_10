package demo.ce316.terminal;

import javafx.scene.web.WebEngine;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * Terminal backend for macOS and Linux.
 *
 * <p>Starts a persistent {@code /bin/bash --norc --noprofile} process from
 * the user's home directory. Sentinels are emitted with standard POSIX shell
 * syntax after each command.
 */
public final class UnixTerminalBackend extends AbstractTerminalBackend {

    private static final String SHELL_PATH =
        "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin:/opt/homebrew/sbin";

    public UnixTerminalBackend(WebEngine webEngine) {
        super(webEngine);
        startShell();
    }

    // ── Shell lifecycle ───────────────────────────────────────────────────────

    @Override
    protected void startShell() {
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "--norc", "--noprofile");
            pb.environment().put("TERM",     "dumb");
            pb.environment().put("PS1",      "");
            pb.environment().put("HISTFILE", "");

            String envPath = System.getenv("PATH");
            pb.environment().put("PATH",
                SHELL_PATH + (envPath != null ? ":" + envPath : ""));

            pb.directory(new File(System.getProperty("user.home")));
            pb.redirectErrorStream(true);

            process = pb.start();
            stdin   = new PrintWriter(
                new OutputStreamWriter(process.getOutputStream()), true);

            startReader();

            // Emit initial CWD + DONE so the JS prompt is correct from the start
            stdin.println("echo '" + SEN_CWD + "'\"$(pwd)\"");
            stdin.println("echo '" + SEN_DONE + "0'");
            stdin.flush();

        } catch (Exception e) {
            System.err.println("UnixTerminalBackend: startShell failed — " + e.getMessage());
        }
    }

    // ── TerminalBackend ───────────────────────────────────────────────────────

    @Override
    public void send(String cmd) {
        if (!isAlive()) startShell();
        stdin.println(cmd);
        // Capture exit code in $__iae_rc before the two sentinel echoes
        stdin.println("__iae_rc=$?; " +
                      "echo '" + SEN_CWD  + "'\"$(pwd)\"; " +
                      "echo '" + SEN_DONE + "'\"$__iae_rc\"");
        stdin.flush();
    }

    @Override
    public void interrupt() {
        if (isAlive()) {
            stdin.print('');   // ETX = Ctrl+C
            stdin.println();
            stdin.flush();
        }
    }
}
