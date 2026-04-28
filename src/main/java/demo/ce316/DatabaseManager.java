package demo.ce316;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

public class DatabaseManager {

    private static DatabaseManager instance;
    private Connection conn;

    private DatabaseManager() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dir = new File(System.getProperty("user.home"), ".iae");
            dir.mkdirs();
            File dbFile = new File(dir, "projects.db");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            conn.createStatement().execute("PRAGMA foreign_keys = ON");
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Database initialization failed", e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
    }

    public static DatabaseManager getInstance() {
        if (instance == null) instance = new DatabaseManager();
        return instance;
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS projects (
                    id            TEXT PRIMARY KEY,
                    name          TEXT NOT NULL,
                    lang          TEXT NOT NULL,
                    student_count INTEGER DEFAULT 0
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS students (
                    id              TEXT NOT NULL,
                    project_id      TEXT NOT NULL,
                    name            TEXT NOT NULL,
                    grade           INTEGER DEFAULT 0,
                    status          TEXT DEFAULT 'pending',
                    submission_path TEXT,
                    PRIMARY KEY (id, project_id),
                    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS logs (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id TEXT NOT NULL,
                    time       TEXT NOT NULL,
                    level      TEXT NOT NULL,
                    message    TEXT NOT NULL,
                    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS project_config (
                    project_id TEXT PRIMARY KEY,
                    lang       TEXT    DEFAULT 'C',
                    timeout    INTEGER DEFAULT 5,
                    max_grade  INTEGER DEFAULT 100,
                    flags      TEXT    DEFAULT '-Wall -O2',
                    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
                )
            """);

            // Mevcut DB'ye submission_path kolonu yoksa ekle
            try {
                st.executeUpdate("ALTER TABLE students ADD COLUMN submission_path TEXT");
            } catch (SQLException ignored) {}
        }
    }

    // --- Okuma ---

    public String getProjectsJson() {
        StringBuilder sb = new StringBuilder("[");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id, name, lang, student_count FROM projects ORDER BY rowid")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                sb.append(String.format(
                    "{\"id\":\"%s\",\"name\":\"%s\",\"lang\":\"%s\",\"studentCount\":%d}",
                    esc(rs.getString("id")),
                    esc(rs.getString("name")),
                    esc(rs.getString("lang")),
                    rs.getInt("student_count")));
                first = false;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sb.append("]").toString();
    }

    public String getStudentsJson(String projectId) {
        StringBuilder sb = new StringBuilder("[");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, grade, status, submission_path FROM students WHERE project_id = ? ORDER BY name")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                String path = rs.getString("submission_path");
                sb.append(String.format(
                    "{\"id\":\"%s\",\"name\":\"%s\",\"grade\":%d,\"status\":\"%s\",\"submissionPath\":\"%s\"}",
                    esc(rs.getString("id")),
                    esc(rs.getString("name")),
                    rs.getInt("grade"),
                    esc(rs.getString("status")),
                    esc(path != null ? path : "")));
                first = false;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sb.append("]").toString();
    }

    public String getStudentCodeJson(String studentId, String projectId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT submission_path FROM students WHERE id = ? AND project_id = ?")) {
            ps.setString(1, studentId);
            ps.setString(2, projectId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return "{\"error\":\"Student not found\"}";

            String pathStr = rs.getString("submission_path");
            if (pathStr == null || pathStr.isBlank())
                return "{\"error\":\"No submission path set for this student\"}";

            Path filePath = Path.of(pathStr);
            if (!Files.exists(filePath))
                return "{\"error\":\"File not found: " + esc(pathStr) + "\"}";

            String content = Files.readString(filePath);
            String filename = filePath.getFileName().toString();
            String lang = detectLang(filename);

            return String.format(
                "{\"filename\":\"%s\",\"lang\":\"%s\",\"path\":\"%s\",\"code\":\"%s\"}",
                esc(filename), lang, esc(pathStr), escCode(content));

        } catch (SQLException | IOException e) {
            e.printStackTrace();
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    public String getLogsJson(String projectId) {
        StringBuilder sb = new StringBuilder("[");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT time, level, message FROM logs WHERE project_id = ? ORDER BY id")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                sb.append(String.format(
                    "{\"time\":\"%s\",\"level\":\"%s\",\"message\":\"%s\"}",
                    esc(rs.getString("time")),
                    esc(rs.getString("level")),
                    esc(rs.getString("message"))));
                first = false;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sb.append("]").toString();
    }

    public String getConfigJson(String projectId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT lang, timeout, max_grade, flags FROM project_config WHERE project_id = ?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return String.format(
                    "{\"lang\":\"%s\",\"timeout\":%d,\"maxGrade\":%d,\"flags\":\"%s\"}",
                    esc(rs.getString("lang")),
                    rs.getInt("timeout"),
                    rs.getInt("max_grade"),
                    esc(rs.getString("flags")));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return "{\"lang\":\"C\",\"timeout\":5,\"maxGrade\":100,\"flags\":\"-Wall -O2\"}";
    }

    // --- Yazma ---

    public void saveConfig(String projectId, String lang, int timeout, int maxGrade, String flags) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO project_config (project_id, lang, timeout, max_grade, flags)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET
                    lang=excluded.lang, timeout=excluded.timeout,
                    max_grade=excluded.max_grade, flags=excluded.flags
            """)) {
            ps.setString(1, projectId);
            ps.setString(2, lang);
            ps.setInt(3, timeout);
            ps.setInt(4, maxGrade);
            ps.setString(5, flags);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void updateGrade(String studentId, String projectId, int grade) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE students SET grade = ?, status = ? WHERE id = ? AND project_id = ?")) {
            ps.setInt(1, grade);
            ps.setString(2, grade >= 60 ? "success" : "failed");
            ps.setString(3, studentId);
            ps.setString(4, projectId);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void addLog(String projectId, String time, String level, String message) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO logs (project_id, time, level, message) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, projectId);
            ps.setString(2, time);
            ps.setString(3, level);
            ps.setString(4, message);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException e) { e.printStackTrace(); }
    }

    // --- Yardımcı ---

    private String detectLang(String filename) {
        if (filename == null) return "Text";
        String f = filename.toLowerCase();
        if (f.endsWith(".c"))             return "C";
        if (f.endsWith(".h"))             return "C Header";
        if (f.endsWith(".cpp") || f.endsWith(".cc") || f.endsWith(".cxx")) return "C++";
        if (f.endsWith(".java"))          return "Java";
        if (f.endsWith(".py"))            return "Python";
        if (f.endsWith(".js"))            return "JavaScript";
        if (f.endsWith(".ts"))            return "TypeScript";
        if (f.endsWith(".cs"))            return "C#";
        if (f.endsWith(".go"))            return "Go";
        if (f.endsWith(".rs"))            return "Rust";
        if (f.endsWith(".kt"))            return "Kotlin";
        if (f.endsWith(".rb"))            return "Ruby";
        if (f.endsWith(".php"))           return "PHP";
        if (f.endsWith(".swift"))         return "Swift";
        return "txt";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // Kod içeriği için daha kapsamlı escape
    private String escCode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
