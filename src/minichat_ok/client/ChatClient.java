package minichat_ok.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import minichat_ok.common.Protocol;

/**
 * Kết nối TCP phía client tới {@code ChatServer} (giai đoạn 3).
 *
 * <p>Giao thức: văn bản UTF-8, mỗi khung tin là một dòng kết thúc bằng {@code '\n'}
 * (xem {@link Protocol}). Dữ liệu được nhận trên luồng nền riêng
 * ("client-receiver") nên giao diện Swing không bị treo. Sự kiện được chuyển ra
 * ngoài qua {@link ChatClientListener}.</p>
 *
 * <p>Các callback đều được gọi từ luồng nền; lớp giao diện phải tự chuyển sang
 * EDT (ví dụ {@code SwingUtilities.invokeLater}) trước khi cập nhật Swing.</p>
 */
public class ChatClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** Thời gian tối đa (ms) chờ Server chấp nhận yêu cầu tham gia. */
    private static final int JOIN_TIMEOUT_MS = 60_000;

    private final ChatClientListener listener;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread receiverThread;

    /** Khóa báo kết quả duyệt (JOIN_OK / JOIN_DENY) từ luồng nhận dữ liệu. */
    private final Object joinLock = new Object();

    private volatile boolean connected;
    private volatile boolean intentionalDisconnect;
    private volatile boolean joinApproved;
    private volatile String joinDenyReason;
    private volatile String clientName;
    private String host;
    private int port;

    /**
     * @param listener nơi nhận sự kiện; có thể là {@code null} nếu không cần sự kiện nào
     */
    public ChatClient(ChatClientListener listener) {
        this.listener = (listener != null) ? listener : new ChatClientListener() {
            // Không quan tâm sự kiện nào.
        };
    }

    /**
     * Constructor tương thích với giai đoạn 2 (bốn callback riêng lẻ).
     *
     * @param onChatMessage    nhận (tên người gửi, nội dung) của tin nhắn công khai
     * @param onSystemMessage  nhận thông báo hệ thống (tham gia, rời phòng, server dừng...)
     * @param onNameAssigned   nhận tên định danh do server cấp ngay sau khi kết nối
     * @param onConnectionLost nhận lý do khi kết nối bị mất ngoài ý muốn
     */
    public ChatClient(BiConsumer<String, String> onChatMessage,
                      Consumer<String> onSystemMessage,
                      Consumer<String> onNameAssigned,
                      Consumer<String> onConnectionLost) {
        this(new ChatClientListener() {
            @Override
            public void onChatMessage(String sender, String text) {
                if (onChatMessage != null) {
                    onChatMessage.accept(sender, text);
                }
            }

            @Override
            public void onSystemMessage(String message) {
                if (onSystemMessage != null) {
                    onSystemMessage.accept(message);
                }
            }

            @Override
            public void onNameAssigned(String name) {
                if (onNameAssigned != null) {
                    onNameAssigned.accept(name);
                }
            }

            @Override
            public void onConnectionLost(String reason) {
                if (onConnectionLost != null) {
                    onConnectionLost.accept(reason);
                }
            }
        });
    }

    /**
     * Mở kết nối tới server. Đây là thao tác blocking nên phải gọi từ luồng nền
     * để không làm treo giao diện.
     *
     * @throws IOException nếu không kết nối được (server chưa chạy, sai IP/cổng, timeout...)
     * @throws IllegalStateException nếu client đang kết nối
     * @throws IllegalArgumentException nếu IP hoặc cổng không hợp lệ
     */
    public synchronized void connect(String host, int port) throws IOException {
        if (connected) {
            throw new IllegalStateException("Client đã kết nối tới " + this.host + ":" + this.port);
        }
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Địa chỉ IP không được để trống.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng không hợp lệ: " + port + " (phải nằm trong khoảng 1..65535)");
        }

        String targetHost = host.trim();
        Socket newSocket = new Socket();
        try {
            newSocket.connect(new InetSocketAddress(targetHost, port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            closeQuietly(newSocket);
            throw e;
        }

        this.socket = newSocket;
        this.host = targetHost;
        this.port = port;
        this.in = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8), true);
        this.clientName = null;
        this.joinApproved = false;
        this.joinDenyReason = null;
        this.intentionalDisconnect = false;
        this.connected = true;

        Thread thread = new Thread(this::receiveLoop, "client-receiver");
        thread.setDaemon(true);
        this.receiverThread = thread;
        thread.start();

        // Gửi yêu cầu tham gia và chờ Server quyết định (chưa vào phòng ngay).
        try {
            sendLine(Protocol.joinRequestMessage());
        } catch (IOException e) {
            intentionalDisconnect = true;
            closeResources();
            connected = false;
            throw e;
        }

        if (!awaitJoinDecision(JOIN_TIMEOUT_MS)) {
            String reason = (joinDenyReason != null) ? joinDenyReason
                    : "Hết thời gian chờ Server chấp nhận yêu cầu kết nối.";
            // Chủ động đóng nên receiveLoop không báo "mất kết nối" lần nữa.
            intentionalDisconnect = true;
            closeResources();
            connected = false;
            clientName = null;
            throw new IOException(reason);
        }
    }

    /**
     * Chờ Server trả lời {@code JOIN_OK} hoặc {@code JOIN_DENY}.
     *
     * @param timeoutMs thời gian chờ tối đa tính bằng mili giây
     * @return {@code true} nếu Server chấp nhận
     * @throws IOException nếu luồng đang chờ bị ngắt
     */
    private boolean awaitJoinDecision(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (joinLock) {
            while (!joinApproved && joinDenyReason == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    joinLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Đã bị ngắt khi chờ Server chấp nhận kết nối.");
                }
            }
            return joinApproved;
        }
    }

    /**
     * Vòng lặp nhận dữ liệu, chạy trên luồng nền "client-receiver".
     */
    private void receiveLoop() {
        String reason = "Server đã đóng kết nối.";
        // Giữ tham chiếu cục bộ: disconnect() có thể đặt field in = null từ luồng khác.
        BufferedReader reader = this.in;
        if (reader == null) {
            return;
        }
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                handleIncomingLine(line);
            }
        } catch (IOException e) {
            reason = "Lỗi khi nhận dữ liệu: " + e.getMessage();
        } finally {
            boolean unexpected = !intentionalDisconnect;
            closeResources();
            connected = false;
            if (unexpected) {
                listener.onConnectionLost(reason);
            }
        }
    }

    /**
     * Phân tích một dòng giao thức nhận từ server và gọi callback tương ứng.
     */
    private void handleIncomingLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }

        String type = Protocol.typeOf(line);
        switch (type) {
            case Protocol.TYPE_CHAT -> {
                String[] parts = Protocol.fields(line);
                if (parts.length >= 3) {
                    listener.onChatMessage(parts[1], parts[2]);
                } else if (parts.length == 2) {
                    // Dòng MSG thiếu tên người gửi: vẫn hiển thị để không mất tin.
                    listener.onChatMessage("Server", parts[1]);
                }
            }
            case Protocol.TYPE_JOIN_OK -> {
                String name = Protocol.payload(line).trim();
                if (!name.isEmpty()) {
                    clientName = name;
                }
                synchronized (joinLock) {
                    joinApproved = true;
                    joinLock.notifyAll();
                }
                if (clientName != null) {
                    listener.onNameAssigned(clientName);
                }
            }
            case Protocol.TYPE_JOIN_DENY -> {
                String reason = Protocol.payload(line).trim();
                joinDenyReason = reason.isEmpty()
                        ? "Server đã từ chối yêu cầu kết nối."
                        : reason;
                // Bị từ chối là kết thúc có chủ đích: không báo "mất kết nối".
                intentionalDisconnect = true;
                synchronized (joinLock) {
                    joinLock.notifyAll();
                }
            }
            case Protocol.TYPE_NAME -> {
                String name = Protocol.payload(line).trim();
                if (!name.isEmpty()) {
                    clientName = name;
                    listener.onNameAssigned(name);
                }
            }
            case Protocol.TYPE_SYSTEM -> listener.onSystemMessage(Protocol.payload(line));
            case Protocol.TYPE_USERS ->
                    listener.onUserList(Protocol.parseNameList(Protocol.payload(line)));
            case Protocol.TYPE_PRIVATE -> {
                String[] parts = Protocol.fields(line, 4);
                if (parts.length >= 4) {
                    listener.onPrivateMessage(parts[1], parts[2], parts[3]);
                } else if (parts.length == 3) {
                    listener.onPrivateMessage(parts[1], "", parts[2]);
                } else {
                    listener.onSystemMessage("Dòng PRIV không hợp lệ: " + line);
                }
            }
            case Protocol.TYPE_GROUP -> {
                String[] parts = Protocol.fields(line);
                if (parts.length >= 3 && !parts[1].trim().isEmpty()) {
                    listener.onGroupCreated(parts[1].trim(), Protocol.parseNameList(parts[2]));
                } else {
                    listener.onSystemMessage("Dòng GROUP không hợp lệ: " + line);
                }
            }
            case Protocol.TYPE_GROUP_MESSAGE -> {
                String[] parts = Protocol.fields(line, 4);
                if (parts.length >= 4) {
                    listener.onGroupMessage(parts[1], parts[2], parts[3]);
                } else {
                    listener.onSystemMessage("Dòng GMSG không hợp lệ: " + line);
                }
            }
            case Protocol.TYPE_GROUP_LEFT -> {
                String[] parts = Protocol.fields(line, 4);
                if (parts.length >= 4) {
                    listener.onGroupMemberLeft(parts[1], parts[2], parts[3]);
                } else {
                    listener.onSystemMessage("Dòng GLEFT không hợp lệ: " + line);
                }
            }
            case Protocol.TYPE_ERROR -> {
                String[] parts = Protocol.fields(line);
                String code = (parts.length >= 2) ? parts[1] : "ERROR";
                String message = (parts.length >= 3) ? parts[2] : Protocol.payload(line);
                listener.onError(code, message);
            }
            default -> {
                // Loại không nhận biết: hiển thị như thông báo hệ thống thay vì bỏ mất dữ liệu.
                listener.onSystemMessage(line);
            }
        }
    }

    /**
     * Gửi một tin nhắn công khai lên server.
     *
     * @param message nội dung tin nhắn (ký tự xuống dòng sẽ được thay bằng dấu cách)
     * @throws IOException              nếu chưa kết nối hoặc không gửi được
     * @throws IllegalArgumentException nếu nội dung rỗng hoặc quá dài
     */
    public void sendMessage(String message) throws IOException {
        sendLine(Protocol.clientChatMessage(validateMessage(message, "tin nhắn")));
    }

    /**
     * Gửi tin nhắn riêng 1-1 tới một client khác.
     *
     * <p>Server là bên kiểm tra người nhận có tồn tại hay không và gửi bản echo lại
     * cho cả hai bên; client không tự hiển thị nội dung trước khi nhận echo.</p>
     *
     * @param targetName tên người nhận (ví dụ {@code "Client-2"})
     * @param message    nội dung tin nhắn
     * @throws IOException              nếu chưa kết nối hoặc không gửi được
     * @throws IllegalArgumentException nếu người nhận/nội dung không hợp lệ
     */
    public void sendPrivateMessage(String targetName, String message) throws IOException {
        if (targetName == null || targetName.trim().isEmpty()) {
            throw new IllegalArgumentException("Chưa chọn người nhận tin riêng.");
        }
        sendLine(Protocol.privateMessage(targetName.trim(), validateMessage(message, "tin nhắn riêng")));
    }

    /**
     * Yêu cầu tạo nhóm với danh sách thành viên được mời.
     *
     * <p>Client kiểm tra sơ bộ số thành viên; server kiểm tra lại toàn bộ (thành viên
     * tồn tại, đủ số lượng tối thiểu {@link Protocol#MIN_GROUP_MEMBERS}, giới hạn nhóm).</p>
     *
     * @param memberNames tên các thành viên được mời (không gồm chính mình)
     * @throws IOException              nếu chưa kết nối hoặc không gửi được
     * @throws IllegalArgumentException nếu danh sách không đủ thành viên
     */
    public void createGroup(List<String> memberNames) throws IOException {
        List<String> cleaned = Protocol.parseNameList(Protocol.joinNames(memberNames));
        if (cleaned.size() + 1 < Protocol.MIN_GROUP_MEMBERS) {
            throw new IllegalArgumentException("Nhóm cần ít nhất " + Protocol.MIN_GROUP_MEMBERS
                    + " thành viên (bạn và " + (Protocol.MIN_GROUP_MEMBERS - 1) + " người khác).");
        }
        sendLine(Protocol.groupCreateMessage(cleaned));
    }

    /**
     * Gửi tin nhắn tới tất cả thành viên của một nhóm.
     *
     * @param groupId mã nhóm, ví dụ {@code "G1"}
     * @param message nội dung tin nhắn
     * @throws IOException              nếu chưa kết nối hoặc không gửi được
     * @throws IllegalArgumentException nếu mã nhóm/nội dung không hợp lệ
     */
    public void sendGroupMessage(String groupId, String message) throws IOException {
        if (groupId == null || groupId.trim().isEmpty()) {
            throw new IllegalArgumentException("Chưa chọn nhóm.");
        }
        sendLine(Protocol.groupChatMessage(groupId.trim(), validateMessage(message, "tin nhắn nhóm")));
    }

    /**
     * Yêu cầu rời một nhóm.
     *
     * @param groupId mã nhóm
     * @throws IOException              nếu chưa kết nối hoặc không gửi được
     * @throws IllegalArgumentException nếu mã nhóm rỗng
     */
    public void leaveGroup(String groupId) throws IOException {
        if (groupId == null || groupId.trim().isEmpty()) {
            throw new IllegalArgumentException("Chưa chọn nhóm.");
        }
        sendLine(Protocol.groupLeaveMessage(groupId.trim()));
    }

    /**
     * Yêu cầu server gửi lại danh sách client đang online.
     *
     * @throws IOException nếu chưa kết nối hoặc không gửi được
     */
    public void requestUserList() throws IOException {
        sendLine(Protocol.TYPE_USERS);
    }

    private String validateMessage(String message, String label) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Nội dung " + label + " không được để trống.");
        }
        String text = message.replace('\r', ' ').replace('\n', ' ');
        if (text.length() > Protocol.MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Tin nhắn quá dài (" + text.length() + " ký tự). Tối đa "
                    + Protocol.MAX_MESSAGE_LENGTH + " ký tự.");
        }
        return text;
    }

    /**
     * Ghi một dòng giao thức lên socket và kiểm tra lỗi.
     *
     * @param line dòng đã dựng theo {@link Protocol}
     * @throws IOException nếu chưa kết nối hoặc không gửi được
     */
    private void sendLine(String line) throws IOException {
        // Giữ tham chiếu cục bộ để tránh race với disconnect() trên luồng khác.
        PrintWriter writer = this.out;
        if (!connected || writer == null) {
            throw new IOException("Chưa kết nối tới server.");
        }

        // Luôn dùng '\n' để thống nhất ký tự xuống dòng với server.
        writer.print(line + "\n");
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Không gửi được tin nhắn, kết nối có thể đã bị ngắt.");
        }
    }

    /**
     * Ngắt kết nối chủ động. Callback {@code onConnectionLost} sẽ không được gọi.
     */
    public synchronized void disconnect() {
        intentionalDisconnect = true;
        closeResources();
        connected = false;
        clientName = null;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * @return tên định danh do server cấp (ví dụ {@code "Client-1"}), hoặc {@code null}
     *         nếu chưa nhận được
     */
    public String getClientName() {
        return clientName;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    private synchronized void closeResources() {
        try {
            if (socket != null && !socket.isClosed()) {
                // Đóng socket trước để đánh thức luồng đang chờ đọc dữ liệu.
                socket.close();
            }
        } catch (IOException ignored) {
            // Không còn gì để xử lý khi đóng socket.
        }

        if (out != null) {
            out.close();
        }
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
            // Luồng đọc đã bị đóng theo socket.
        }

        out = null;
        in = null;
        socket = null;
        receiverThread = null;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socket chưa được kết nối hoặc đã đóng.
        }
    }
}
