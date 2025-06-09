module com.ouroboros.chatapp.distributedsystem {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.kordamp.bootstrapfx.core;

    opens com.ouroboros.chatapp.distributedsystem to javafx.fxml;
    exports com.ouroboros.chatapp.distributedsystem;
}