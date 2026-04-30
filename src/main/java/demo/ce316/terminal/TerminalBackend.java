package demo.ce316.terminal;

/**
 * Contract for a persistent interactive-shell backend.
 *
 * Implementations are created by {@link TerminalSession} based on the OS
 * detected by {@link OsDetector}; callers never need to know which backend
 * is in use.
 */
public interface TerminalBackend {

    /**
     * Send a user command to the shell.
     * The implementation must append the sentinel lines that signal
     * CWD update and command completion to the JS side.
     */
    void send(String cmd);

    /**
     * Interrupt the currently running command (equivalent to Ctrl+C).
     */
    void interrupt();

    /**
     * Forcibly terminate the shell process.
     */
    void destroy();

    /**
     * Returns {@code true} if the underlying shell process is still alive.
     */
    boolean isAlive();
}
