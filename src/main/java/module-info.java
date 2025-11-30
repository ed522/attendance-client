module com.ed522.bcr2200.attendance {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;


    opens com.ed522.bcr2200.attendance to javafx.fxml;
    exports com.ed522.bcr2200.attendance;
}