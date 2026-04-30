package demo.ce316.terminal;

/**
 * Singleton that detects the host operating system once at startup.
 *
 * Usage:
 *   OsDetector.getInstance().isWindows()
 *   OsDetector.getInstance().getOs()
 */
public final class OsDetector {

    public enum OS { WINDOWS, MAC, LINUX, UNKNOWN }

    // Initialised once by the class-loader — guaranteed thread-safe
    private static final OsDetector INSTANCE = new OsDetector();

    private final OS os;

    private OsDetector() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if      (name.contains("win"))                                        os = OS.WINDOWS;
        else if (name.contains("mac") || name.contains("darwin"))             os = OS.MAC;
        else if (name.contains("nix") || name.contains("nux")
              || name.contains("linux") || name.contains("aix"))              os = OS.LINUX;
        else                                                                   os = OS.UNKNOWN;

        System.out.println("OsDetector: detected OS = " + os
                           + " (os.name=" + System.getProperty("os.name") + ")");
    }

    public static OsDetector getInstance() { return INSTANCE; }

    public OS      getOs()      { return os; }
    public boolean isWindows()  { return os == OS.WINDOWS; }
    public boolean isMac()      { return os == OS.MAC; }
    public boolean isLinux()    { return os == OS.LINUX; }
    /** True on any POSIX-like system (macOS or Linux). */
    public boolean isUnix()     { return os == OS.MAC || os == OS.LINUX; }
}
