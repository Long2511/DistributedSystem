package com.ouroboros.chatapp.distributedsystem;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatServerController {
    @FXML private TextField portField;
    @FXML private Button startButton;
    @FXML private Label statusLabel;
    @FXML private ListView<String> clientsList;
    @FXML private TextArea logArea;
    @FXML private TextField serverMessageField;
    @FXML private Button serverSendButton;

    private ServerRunnable serverRunnable;
    private Thread serverThread;
    private ObservableList<String> clients = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        portField.setText("8888");
        clientsList.setItems(clients);
        startButton.setOnAction(_ -> startServer());
        serverSendButton.setOnAction(_ -> sendServerMessageToSelected());
        serverMessageField.setOnAction(_ -> sendServerMessageToSelected());
    }

    private void startServer() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            showError("Port must be a number.");
            return;
        }
        startButton.setDisable(true);
        statusLabel.setText("Server running on port " + port);
        log("Server started on port " + port);
        serverRunnable = new ServerRunnable(port, clients, this::log);
        serverThread = new Thread(serverRunnable);
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void log(String msg) {
        Platform.runLater(() -> logArea.appendText(msg + "\n"));
    }

    private void showError(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
            alert.showAndWait();
        });
    }

    private void sendServerMessageToSelected() {
        String msg = serverMessageField.getText().trim();
        Integer selectedIdx = clientsList.getSelectionModel().getSelectedIndex();
        String selectedClient = clientsList.getSelectionModel().getSelectedItem();
        if (msg.isEmpty() || serverRunnable == null) return;
        if (selectedIdx == null || selectedIdx < 0) {
            // broadcast to all
            serverRunnable.broadcastFromServer(msg);
            log("[Server to ALL]: " + msg);
        } else {
            // Send to specific client
            serverRunnable.sendToSpecificClient(selectedClient, msg);
            log("[Server to " + selectedClient + "]: " + msg);
        }
        serverMessageField.clear();
        clientsList.getSelectionModel().clearSelection();
    }


    public static class ServerRunnable implements Runnable {
        private final int port;
        private final ObservableList<String> clients;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final List<ClientHandler> clientHandlers = new CopyOnWriteArrayList<>();
        private final Map<String, ClientHandler> clientMap = new ConcurrentHashMap<>();
        private final java.util.function.Consumer<String> logger;
        private ServerSocket serverSocket;
        private volatile boolean running = true;

        public ServerRunnable(int port, ObservableList<String> clients, java.util.function.Consumer<String> logger) {
            this.port = port;
            this.clients = clients;
            this.logger = logger;
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(port);
                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    ClientHandler handler = new ClientHandler(clientSocket, clients, logger, this);
                    clientHandlers.add(handler);
                    pool.execute(() -> handler.run());
                }
            } catch (IOException e) {
                logger.accept("Server error: " + e.getMessage());
            }
        }
        public void broadcastFromServer(String msg) {
            for (ClientHandler handler : clientHandlers) {
                handler.sendFromServer(msg);
            }
        }
        public void sendToSpecificClient(String clientInfo, String msg) {
            ClientHandler handler = clientMap.get(clientInfo);
            if (handler != null) {
                handler.sendFromServer(msg);
            }
        }
        public void removeHandler(ClientHandler handler) {
            clientHandlers.remove(handler);
            if (handler.getClientInfo() != null) {
                clientMap.remove(handler.getClientInfo());
            }
        }
        public void registerHandler(String clientInfo, ClientHandler handler) {
            clientMap.put(clientInfo, handler);
        }
    }


    public static class ClientHandler implements Runnable {
        private final Socket socket;
        private final ObservableList<String> clients;
        private final java.util.function.Consumer<String> logger;
        private final ServerRunnable server;
        private PrintWriter out;
        private BufferedReader in;
        private String nickname;
        private String clientInfo;
        private static final String DISCONNECT_REQUEST = "bye";
        private static final String DISCONNECT_ACK = "bye";

        public ClientHandler(Socket socket, ObservableList<String> clients, java.util.function.Consumer<String> logger, ServerRunnable server) {
            this.socket = socket;
            this.clients = clients;
            this.logger = logger;
            this.server = server;
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                nickname = in.readLine();
                clientInfo = nickname + " (" + socket.getInetAddress().getHostAddress() + ":" + socket.getPort() + ")";
                Platform.runLater(() -> clients.add(clientInfo));
                logger.accept("Client connected: " + clientInfo);
                out.println("Hello " + nickname);
                server.registerHandler(clientInfo, this);
                String line;
                while ((line = in.readLine()) != null) {
                    logger.accept(nickname + ": " + line);
                    if (line.equals(DISCONNECT_REQUEST)) {
                        out.println(DISCONNECT_ACK); // Send ACK back to client
                        logger.accept("Sent disconnect ACK to " + nickname);
                        break;
                    } else if (line.equals(DISCONNECT_ACK)) {
                        logger.accept("Received disconnect ACK from " + nickname);
                        break;
                    }
                    out.println("Echo: " + line);
                }
            } catch (IOException e) {
                logger.accept("Connection error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                logger.accept("Client disconnected: " + clientInfo);
                Platform.runLater(() -> clients.remove(clientInfo));
                server.removeHandler(this);
            }
        }

        public String getClientInfo() {
            return clientInfo;
        }

        public void sendFromServer(String msg) {
            if (out != null) {
                out.println(msg);
                if (msg.trim().equalsIgnoreCase(DISCONNECT_REQUEST)) {
                    logger.accept("Server initiated disconnect for " + nickname);
                    new Thread(() -> {
                        try {
                            String ack = in.readLine();
                            if (ack != null && ack.trim().equalsIgnoreCase(DISCONNECT_ACK)) {
                                logger.accept("Received disconnect ACK from " + nickname);
                            }
                        } catch (IOException ignored) {}
                        try { socket.close(); } catch (IOException ignored) {}
                        Platform.runLater(() -> clients.remove(clientInfo));
                        server.removeHandler(this);
                    }).start();
                }
            }
        }
    }
}
