package com.ouroboros.chatapp.distributedsystem;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.io.*;
import java.net.*;

public class ChatClientController {
    @FXML private TextField nicknameField;
    @FXML private TextField serverAddressField;
    @FXML private TextField portField;
    @FXML private Button connectButton;
    @FXML private Button disconnectButton;
    @FXML private TextArea chatArea;
    @FXML private TextField messageField;
    @FXML private Button sendButton;
    @FXML private Label machineInfoLabel;

    private Socket tcpSocket;
    private PrintWriter out;
    private BufferedReader in;
    private Thread receiveThread;
    private boolean connected = false;
    private static final String DISCONNECT_REQUEST = "bye";
    private static final String DISCONNECT_ACK = "bye";
    private boolean waitingForAck = false;

    @FXML
    public void initialize() {
        nicknameField.setText("");
        serverAddressField.setText("localhost");
        portField.setText("8888");
        connectButton.setOnAction(_ -> connect());
        disconnectButton.setOnAction(_ -> disconnect());
        sendButton.setOnAction(_ -> sendMessage());
        messageField.setOnAction(_ -> sendMessage());
        setConnected(false);
    }

    private void setConnected(boolean value) {
        connected = value;
        connectButton.setDisable(value);
        disconnectButton.setDisable(!value);
        sendButton.setDisable(!value);
        messageField.setDisable(!value);
        nicknameField.setDisable(value);
        serverAddressField.setDisable(value);
        portField.setDisable(value);
    }

    private void connect() {
        String nickname = nicknameField.getText().trim();
        String address = serverAddressField.getText().trim();
        String portStr = portField.getText().trim();
        if (nickname.isEmpty() || address.isEmpty() || portStr.isEmpty()) {
            showError("Please fill in all fields.");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            showError("Port must be a number.");
            return;
        }
        try {
            tcpSocket = new Socket(address, port);
            out = new PrintWriter(tcpSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            setConnected(true);
            chatArea.appendText("Connected to server using userid: " + nickname + "\n");
            // Get real local IP address
            String localIp = null;
            try {
                localIp = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                localIp = tcpSocket.getLocalAddress().getHostAddress();
            }
            machineInfoLabel.setText(String.format("%s | Local: %s:%d | Remote: %s:%d", nickname, localIp, tcpSocket.getLocalPort(), tcpSocket.getInetAddress().getHostAddress(), tcpSocket.getPort()));
            out.println(nickname);
            receiveThread = new Thread(this::receiveMessages);
            receiveThread.setDaemon(true);
            receiveThread.start();
        } catch (IOException ex) {
            showError("Connection failed: " + ex.getMessage());
        }
    }

    private void receiveMessages() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals(DISCONNECT_REQUEST)) {
                    Platform.runLater(() -> chatArea.appendText("Received disconnect request. Sending ACK and disconnecting...\n"));
                    Platform.runLater(this::disconnect);
                    break;
                } else if (line.equals(DISCONNECT_ACK)) {
                    Platform.runLater(() -> chatArea.appendText("Received disconnect ACK. Disconnecting...\n"));
                    Platform.runLater(this::disconnect);
                    break;
                } else {
                    String msg = "Received: " + line + "\n";
                    Platform.runLater(() -> chatArea.appendText(msg));
                }
            }
        } catch (IOException e) {
            Platform.runLater(() -> showError("Connection lost: " + e.getMessage()));
        }
    }

    private void sendMessage() {
        if (!connected) return;
        String msg = messageField.getText().trim();
        if (msg.isEmpty()) return;
        chatArea.appendText("Me: " + msg + "\n");
        if (msg.equalsIgnoreCase("bye")) {
            waitingForAck = true;
            chatArea.appendText("Sent disconnect request. Waiting for ACK...\n");
            messageField.clear();
            return;
        }
        out.println(msg);
        messageField.clear();
    }

    private void disconnect() {
        setConnected(false);
        try {
            if (out != null && !waitingForAck) out.println(DISCONNECT_REQUEST);
            if (tcpSocket != null) tcpSocket.close();
        } catch (IOException ignored) {}
        chatArea.appendText("Disconnected.\n");
        machineInfoLabel.setText("");
        if (receiveThread != null && receiveThread.isAlive()) {
            receiveThread.interrupt();
        }
        waitingForAck = false;
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        alert.showAndWait();
    }
}