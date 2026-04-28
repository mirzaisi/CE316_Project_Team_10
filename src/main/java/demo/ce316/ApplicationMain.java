package demo.ce316;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;
import javafx.stage.Stage;
import javafx.scene.text.Font;
import netscape.javascript.JSObject;

public class ApplicationMain extends Application {
    @Override
    public void start(Stage stage) {
        Font.loadFont(getClass().getResourceAsStream("MaterialSymbols.ttf"),14);
        WebView webView = new WebView();
        WebEngine webEngine = webView.getEngine();

        var resource = getClass().getResource("index.html");
        if (resource == null) {
            System.err.println("Error: Resource not found!\nmain_page.html not found!");
        } else {
            webEngine.load(resource.toExternalForm());
        }

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) webEngine.executeScript("window");
                window.setMember("app", new JavaBridge());
                webEngine.executeScript("if (typeof loadDashboard === 'function') loadDashboard()");
            }
        });

        Scene scene = new Scene(webView, 1280, 800);
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
            System.out.println("Java: Pipeline başlatılıyor...");
        }

        private int parseInt(String s, int fallback) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) { return fallback; }
        }
    }

    @Override
    public void stop() {
        DatabaseManager.getInstance().close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}