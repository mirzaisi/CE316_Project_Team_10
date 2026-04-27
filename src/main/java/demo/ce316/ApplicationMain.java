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
            }
        });

        Scene scene = new Scene(webView, 1280, 800);
        stage.setTitle("IAE - Integrated Assignment Environment");
        stage.setScene(scene);
        stage.show();
    }

    public class JavaBridge {
        public void runPipeline() {
            System.out.println("Java: Pipeline başlatılıyor...");

        }

        public void saveConfig(String json) {
            System.out.println("Java: Konfigürasyon kaydediliyor: " + json);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}