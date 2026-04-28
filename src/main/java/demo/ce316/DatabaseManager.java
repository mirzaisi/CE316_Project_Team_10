package demo.ce316;

import java.io.File;
import java.sql.*;

public class DatabaseManager {

    private static DatabaseManager instance;
    private Connection conn;

    private DatabaseManager() {
        try {
            File dir = new File(System.getProperty("user.home"), ".iae");
            dir.mkdirs();
            File dbFile = new File(dir, "projects.db");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            conn.createStatement().execute("PRAGMA foreign_keys = ON");
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Database initialization failed", e);
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
                    id           TEXT PRIMARY KEY,
                    name         TEXT NOT NULL,
                    lang         TEXT NOT NULL,
                    student_count INTEGER DEFAULT 0
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS students (
                    id         TEXT NOT NULL,
                    project_id TEXT NOT NULL,
                    name       TEXT NOT NULL,
                    grade      INTEGER DEFAULT 0,
                    status     TEXT DEFAULT 'pending',
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
                "SELECT id, name, grade, status FROM students WHERE project_id = ? ORDER BY name")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                sb.append(String.format(
                    "{\"id\":\"%s\",\"name\":\"%s\",\"grade\":%d,\"status\":\"%s\"}",
                    esc(rs.getString("id")),
                    esc(rs.getString("name")),
                    rs.getInt("grade"),
                    esc(rs.getString("status"))));
                first = false;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sb.append("]").toString();
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
            ps.setString(2, grade >= 50 ? "success" : "failed");
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

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
