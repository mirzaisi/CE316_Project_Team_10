module demo.ce316 {
    requires javafx.controls;
    requires javafx.web;
    requires jdk.jsobject;
    requires java.sql;
    requires org.xerial.sqlitejdbc;
    requires org.slf4j;

    opens demo.ce316 to javafx.fxml;
    exports demo.ce316;
}