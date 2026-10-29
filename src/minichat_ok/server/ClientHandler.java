package minichat_ok.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import minichat_ok.common.Protocol;

/**
 * Xử lý dữ liệu của một client trên một luồng riêng (giai đoạn 3).
 *
 * <p>Khi bắt đầu: gửi {@code NAME|<ten duoc cap>} cho client, đăng ký vào danh sách
 * của {@link ChatServer} rồi server phát thông báo tham gia và danh sách online cho
 * mọi người. Các lệnh được chấp nhận từ client:</p>
 * <ul>
 *   <li>{@code MSG|<noi dung>} – tin nhắn phòng chung (phát cho tất cả);</li>
 *   <li>{@code PRIV|<nguoi nhan>|<noi dung>} – tin nhắn riêng 1-1;</li>
 *   <li>{@code GROUP_CREATE|<thanh vien,...>} – tạo nhóm;</li>
 *   <li>{@code GMSG|<ma nhom>|<noi dung>} – tin nhắn nhóm;</li>
 *   <li>{@code GLEAVE|<ma nhom>} – rời nhóm;</li>
 *   <li>{@code USERS} – yêu cầu danh sách client đang online.</li>
 * </ul>
 *
 * <p>Dòng không có tiền tố được coi là tin nhắn công khai (tương thích với client
 * giai đoạn 1, ví dụ khi thử bằng telnet). Mọi tiền tố lạ khác đều bị từ chối bằng
 * {@code ERR|BAD_REQUEST} để tránh việc nội dung riêng bị phát nhầm vào phòng chung.</p>
 *
 * <p>Lớp này chịu trách nhiệm bảo đảm an toàn luồng khi ghi: mọi lần gửi dữ liệu đều
 * đi qua {@link #sendLine(String)} có khóa riêng và kiểm tra trạng thái đóng.</p>
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ChatServer server;
    private final Consumer<String> logger;
    private final String clientAddress;
    private final String clientName;
    private final Object writeLock = new Object();

    /** Thời gian tối đa (ms) client được phép chờ để gửi yêu cầu tham gia. */
    private static final int JOIN_TIMEOUT_MS = 30_000;

    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean closed;
    private volatile boolean joined;

    /**
     * @param socket     socket đã được accept
     * @param server     server quản lý client này
     * @param logger     nơi nhận nhật ký (có thể null)
     * @param clientName tên định danh do server cấp, ví dụ {@code "Client-1"}
     */
    public ClientHandler(Socket socket, ChatServer server, Consumer<String> logger, String clientName) {
        this.socket = socket;
        this.server = server;
        this.logger = (logger != null) ? logger : message -> {
            // Không có nơi nhận nhật ký: bỏ qua.
        };
        this.clientAddress = ChatServer.describe(socket);
        this.clientName = clientName;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            log("Đã mở luồng dữ liệu cho " + describeSelf());

            // Bước 1: chờ yêu cầu tham gia (JOIN) trong thời hạn cho phép.
            socket.setSoTimeout(JOIN_TIMEOUT_MS);
            String firstLine;
            try {
                firstLine = in.readLine();
            } catch (SocketTimeoutException e) {
                firstLine = null;
            }
            if (firstLine == null || !Protocol.isType(firstLine, Protocol.TYPE_JOIN)) {
                sendLine(Protocol.joinDenyMessage(
                        "Bạn phải gửi JOIN để yêu cầu tham gia phòng chat."));
                log(clientName + " không gửi yêu cầu tham gia hợp lệ, đóng kết nối.");
                return;
            }
            socket.setSoTimeout(0);

            // Bước 2: hỏi người quản trị server có chấp nhận không.
            if (!server.requestJoin(this)) {
                sendLine(Protocol.joinDenyMessage("Server đã từ chối yêu cầu kết nối."));
                return;
            }

            // Bước 3: chỉ khi được chấp nhận mới cấp tên và đăng ký vào phòng.
            if (isClosed() || !sendLine(Protocol.joinOkMessage(clientName))) {
                return;
            }
            joined = true;
            if (!server.registerHandler(this)) {
                return;
            }

            String line;
            while (!closed && (line = in.readLine()) != null) {
                handleLine(line);
            }

            if (!closed) {
                log(clientName + " đã ngắt kết nối.");
            }
        } catch (IOException e) {
            if (!closed) {
                log("Lỗi dữ liệu với " + describeSelf() + ": " + e.getMessage());
            }
        } finally {
            close();
            server.removeHandler(this);
        }
    }

    /**
     * Xử lý một dòng nhận được từ client.
     *
     * <p>Client chỉ được gửi các lệnh được liệt kê ở Javadoc của lớp. Các loại do
     * server sinh ra ({@code NAME}, {@code SYS}, {@code GROUP}, {@code GLEFT},
     * {@code ERR}) bị bỏ qua nếu client tự gửi lên để tránh giả mạo.</p>
     */
    private void handleLine(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        String type = Protocol.typeOf(trimmed);
        switch (type) {
            case Protocol.TYPE_CHAT -> handlePublicText(Protocol.clientChatText(trimmed).trim());
            case Protocol.TYPE_PRIVATE -> handlePrivateMessage(trimmed);
            case Protocol.TYPE_GROUP_MESSAGE -> handleGroupMessage(trimmed);
            case Protocol.TYPE_GROUP_CREATE -> handleGroupCreate(trimmed);
            case Protocol.TYPE_GROUP_LEAVE -> handleGroupLeave(trimmed);
            case Protocol.TYPE_USERS -> {
                log(clientName + " yêu cầu danh sách client đang online.");
                server.sendUserList(this);
            }
            case Protocol.TYPE_NAME, Protocol.TYPE_SYSTEM, Protocol.TYPE_GROUP,
                    Protocol.TYPE_GROUP_LEFT, Protocol.TYPE_ERROR,
                    Protocol.TYPE_JOIN_OK, Protocol.TYPE_JOIN_DENY ->
                    log("Bỏ qua dòng " + type + " không hợp lệ từ " + clientName);
            case Protocol.TYPE_JOIN -> {
                if (joined) {
                    log("Bỏ qua yêu cầu JOIN lặp lại từ " + clientName);
                }
            }
            default -> {
                if (trimmed.indexOf('|') >= 0) {
                    // Tiền tố lạ: không được phát vào phòng chung vì có thể là nội dung riêng.
                    log("Từ chối dòng không hợp lệ từ " + clientName + ": " + trimmed);
                    sendError(Protocol.ERROR_BAD_REQUEST, "Lệnh không hợp lệ.");
                } else {
                    // Tương thích ngược: dòng không có tiền tố là tin nhắn công khai.
                    handlePublicText(trimmed);
                }
            }
        }
    }

    private void handlePublicText(String text) {
        if (!validateText(text)) {
            return;
        }
        log("Nhận từ " + clientName + ": " + text);
        server.broadcastPublicMessage(clientName, text);
    }

    private void handlePrivateMessage(String line) {
        String[] parts = Protocol.fields(line, 3);
        if (parts.length < 3) {
            sendError(Protocol.ERROR_BAD_REQUEST, "Tin nhắn riêng phải có dạng PRIV|nguoi-nhan|noi-dung.");
            return;
        }
        String target = parts[1].trim();
        String text = parts[2].trim();
        if (target.isEmpty()) {
            sendError(Protocol.ERROR_BAD_REQUEST, "Thiếu tên người nhận.");
            return;
        }
        if (!validateText(text)) {
            return;
        }
        server.sendPrivateMessage(clientName, target, text);
    }

    private void handleGroupMessage(String line) {
        String[] parts = Protocol.fields(line, 3);
        if (parts.length < 3) {
            sendError(Protocol.ERROR_BAD_REQUEST, "Tin nhắn nhóm phải có dạng GMSG|ma-nhom|noi-dung.");
            return;
        }
        String groupId = parts[1].trim();
        String text = parts[2].trim();
        if (groupId.isEmpty()) {
            sendError(Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm.");
            return;
        }
        if (!validateText(text)) {
            return;
        }
        server.sendGroupMessage(clientName, groupId, text);
    }

    private void handleGroupCreate(String line) {
        String csv = Protocol.payload(line);
        log("Nhận yêu cầu tạo nhóm từ " + clientName + " với thành viên: " + csv);
        server.createGroup(clientName, Protocol.parseNameList(csv));
    }

    private void handleGroupLeave(String line) {
        String groupId = Protocol.payload(line).trim();
        if (groupId.isEmpty()) {
            sendError(Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm.");
            return;
        }
        server.leaveGroup(clientName, groupId);
    }

    /**
     * Kiểm tra nội dung tin nhắn và gửi lỗi nếu không hợp lệ.
     */
    private boolean validateText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.length() > Protocol.MAX_MESSAGE_LENGTH) {
            log("Từ chối tin nhắn quá dài (" + text.length() + " ký tự) từ " + clientName);
            sendError(Protocol.ERROR_MESSAGE_TOO_LONG,
                    "Tin nhắn quá dài (" + text.length() + " ký tự). Tối đa "
                            + Protocol.MAX_MESSAGE_LENGTH + " ký tự.");
            return false;
        }
        return true;
    }

    private void sendError(String code, String message) {
        sendLine(Protocol.errorMessage(code, message));
    }

    /**
     * Gửi một dòng giao thức tới client này. An toàn khi nhiều luồng cùng gọi
     * (ví dụ nhiều handler cùng broadcast) và không bao giờ ghi lên socket đã đóng.
     *
     * @param line dòng cần gửi (chưa gồm ký tự xuống dòng)
     * @return {@code true} nếu gửi thành công, {@code false} nếu kết nối đã đóng/lỗi
     */
    public boolean sendLine(String line) {
        if (line == null) {
            return false;
        }
        synchronized (writeLock) {
            PrintWriter writer = this.out;
            if (closed || socket.isClosed() || writer == null) {
                return false;
            }
            // Luôn dùng '\n' để hai bên thống nhất ký tự xuống dòng.
            writer.print(line + "\n");
            writer.flush();
            return !writer.checkError();
        }
    }

    /**
     * Đóng kết nối và giải phóng tài nguyên. An toàn khi gọi nhiều lần hoặc từ
     * luồng khác (ví dụ khi server dừng).
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        try {
            if (!socket.isClosed()) {
                // Đóng socket trước để đánh thức luồng đang chờ đọc dữ liệu.
                socket.close();
            }
        } catch (IOException ignored) {
            // Không còn gì để xử lý khi đóng socket.
        }

        if (out != null) {
            out.close();
            out = null;
        }
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
            // Luồng đọc đã bị đóng theo socket.
        }
        in = null;
    }

    public String getClientName() {
        return clientName;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    /**
     * @return {@code true} nếu handler đã được đóng (không nên gửi thêm dữ liệu)
     */
    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    private String describeSelf() {
        return clientName + " (" + clientAddress + ")";
    }

    private void log(String message) {
        logger.accept(message);
    }
}
