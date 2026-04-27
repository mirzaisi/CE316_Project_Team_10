module demo.ce316 {
    requires javafx.controls;
    requires javafx.web;
    requires jdk.jsobject;

    opens demo.ce316 to javafx.fxml;
    exports demo.ce316;
}