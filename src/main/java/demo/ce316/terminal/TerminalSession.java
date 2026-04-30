package demo.ce316.terminal;

import javafx.scene.web.WebEngine;

/**
 * OS-aware factory facade for the terminal backend.
 *
 * <p>Uses {@link OsDetector} (singleton) to select the correct
 * {@link TerminalBackend} implementation at construction time:
 * <ul>
 *   <li>Windows → {@link WindowsTerminalBackend} (cmd.exe)</li>
 *   <li>macOS / Linux → {@link UnixTerminalBackend} (/bin/bash)</li>
 * </ul>
 *
 * <p>All callers (e.g. {@code ApplicationMain.JavaBridge}) interact only
 * with this class and are completely decoupled from OS-specific details.
 */
public final class TerminalSession {

    private final TerminalBackend backend;

    public TerminalSession(WebEngine webEngine) {
        OsDetector os = OsDetector.getInstance();
        if (os.isWindows()) {
            System.out.println("TerminalSession: using WindowsTerminalBackend");
            backend = new WindowsTerminalBackend(webEngine);
        } else {
            System.out.println("TerminalSession: using UnixTerminalBackend (" + os.getOs() + ")");
            backend = new UnixTerminalBackend(webEngine);
        }
    }

    /** Send a user command to the shell. */
    public void send(String cmd)  { backend.send(cmd); }

    /** Send Ctrl+C to interrupt the current command. */
    public void interrupt()       { backend.interrupt(); }

    /** Forcibly terminate the shell process. */
    public void destroy()         { backend.destroy(); }

    /** Whether the underlying shell process is still running. */
    public boolean isAlive()      { return backend.isAlive(); }
}
