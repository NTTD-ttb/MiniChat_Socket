package minichat_ok.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import minichat_ok.common.Protocol;

/**
 * Máy chủ chat giai đoạn 3: một phòng chung, chat riêng 1-1 và chat nhóm.
 *
 * <p>Mỗi client kết nối được xử lý bởi một {@link ClientHandler} trên luồng riêng.
 * Server giữ danh sách client đang kết nối trong {@link CopyOnWriteArrayList} nên
 * việc thêm/xóa client luôn an toàn trong khi một luồng khác đang phát tin nhắn.
 * Tin nhắn công khai ({@code MSG}) được phát tới <b>tất cả</b> client, kể cả người
 * gửi. Tin nhắn riêng ({@code PRIV}) chỉ tới người gửi và người nhận. Tin nhắn nhóm
 * ({@code GMSG}) chỉ tới các thành viên của nhóm.</p>
 *
 * <p>Lớp này không phụ thuộc Swing: mọi thông báo được đẩy ra ngoài qua
 * {@link Consumer} nhận nhật ký do lớp giao diện truyền vào.</p>
 */
public class ChatServer {

    private final Consumer<String> logger;
    private final List<ClientHandler> handlers = new CopyOnWriteArrayList<>();
    private final Map<String, ClientHandler> clientsByName = new ConcurrentHashMap<>();
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final AtomicInteger clientCounter = new AtomicInteger();
    private final AtomicInteger groupCounter = new AtomicInteger();

    /** Các handler đã mở socket nhưng chưa được chấp nhận vào phòng. */
    private final List<ClientHandler> pendingHandlers = new CopyOnWriteArrayList<>();

    /**
     * Người quyết định có cho một client vào phòng hay không. Mặc định chấp nhận
     * tất cả để giữ hành vi cũ khi không có giao diện Server (ví dụ kiểm thử raw socket).
     */
    private volatile JoinApprover joinApprover = (clientName, clientAddress) -> true;

    /** Số thành viên tối đa của một nhóm (chỉ áp dụng khi tạo nhóm). */
    private static final int MAX_GROUP_MEMBERS = 20;

    /** Số nhóm tối đa một client có thể tham gia. */
    private static final int MAX_GROUPS_PER_CLIENT = 10;

    /** Khóa tuần tự hóa việc phát tin để mọi client nhận cùng một thứ tự. */
    private final Object broadcastLock = new Object();

    /** Nơi nhận số client đang kết nối (giao diện có thể đăng ký). */
    private volatile Consumer<Integer> clientCountListener = count -> {
        // Mặc định: không ai quan tâm số lượng client.
    };

    /** Nơi nhận số nhóm hiện có (giao diện Server có thể đăng ký). */
    private volatile Consumer<Integer> groupCountListener = count -> {
        // Mặc định: không ai quan tâm số lượng nhóm.
    };

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    /**
     * @param logger nơi nhận nhật ký hoạt động (có thể là null, khi đó nhật ký bị bỏ qua)
     */
    public ChatServer(Consumer<String> logger) {
        this.logger = (logger != null) ? logger : message -> {
            // Không có nơi nhận nhật ký: bỏ qua.
        };
    }

    /**
     * Đăng ký nơi nhận số client mỗi khi danh sách thay đổi.
     *
     * @param listener hàm nhận số client hiện tại, có thể là null để bỏ đăng ký
     */
    public void setClientCountListener(Consumer<Integer> listener) {
        this.clientCountListener = (listener != null) ? listener : count -> {
            // Bỏ đăng ký.
        };
    }

    /**
     * Đăng ký nơi nhận số nhóm mỗi khi danh sách nhóm thay đổi.
     *
     * @param listener hàm nhận số nhóm hiện tại, có thể là null để bỏ đăng ký
     */
    public void setGroupCountListener(Consumer<Integer> listener) {
        this.groupCountListener = (listener != null) ? listener : count -> {
            // Bỏ đăng ký.
        };
    }

    /**
     * Hàm quyết định chấp nhận hay từ chối một yêu cầu kết nối.
     *
     * <p>Hàm được gọi trên luồng xử lý của chính client đó ({@code client-Client-N}),
     * nên có thể chặn để chờ người quản trị bấm nút. Giao diện Swing phải chuyển
     * hộp thoại sang EDT (ví dụ {@code SwingUtilities.invokeAndWait}) và trả về
     * {@code true} nếu đồng ý.</p>
     */
    @FunctionalInterface
    public interface JoinApprover {

        /**
         * @param clientName    tên định danh server dự kiến cấp
         * @param clientAddress địa chỉ {@code ip:port} của client
         * @return {@code true} nếu cho client vào phòng chat
         */
        boolean approve(String clientName, String clientAddress);
    }

    /**
     * Đăng ký nơi quyết định yêu cầu kết nối. Truyền {@code null} để quay lại
     * chế độ tự động chấp nhận mọi client.
     *
     * @param approver hàm quyết định, có thể là {@code null}
     */
    public void setJoinApprover(JoinApprover approver) {
        this.joinApprover = (approver != null) ? approver : (clientName, clientAddress) -> true;
    }

    /**
     * Khởi động server và trả về ngay sau khi socket đã lắng nghe thành công.
     * Việc chờ client được thực hiện trên luồng nền riêng.
     *
     * @param port cổng lắng nghe (1..65535)
     * @throws IOException nếu không thể mở cổng (ví dụ cổng đang bị chiếm)
     * @throws IllegalStateException nếu server đang chạy
     * @throws IllegalArgumentException nếu cổng không hợp lệ
     */
    public synchronized void start(int port) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng không hợp lệ: " + port + " (phải nằm trong khoảng 1..65535)");
        }
        if (running) {
            throw new IllegalStateException("Server đang chạy ở cổng " + this.port);
        }

        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }

        clientCounter.set(0);
        groupCounter.set(0);
        groups.clear();
        clientsByName.clear();
        this.serverSocket = socket;
        this.port = port;
        this.running = true;

        Thread thread = new Thread(this::acceptLoop, "server-accept");
        thread.setDaemon(true);
        this.acceptThread = thread;
        thread.start();

        log("Server đã khởi động, đang lắng nghe tại cổng " + port);
    }

    /**
     * Dừng server: thông báo cho các client (best effort), đóng ServerSocket để
     * thoát vòng lặp accept và đóng toàn bộ kết nối đang hoạt động. An toàn khi
     * gọi nhiều lần.
     */
    public synchronized void stop() {
        if (!running) {
            log("Server đang không chạy, không cần dừng.");
            return;
        }

        int stoppedPort = this.port;
        running = false;

        // Cố gắng báo cho client biết lý do trước khi đóng kết nối.
        broadcastSystem("Server đang dừng, các kết nối sẽ bị đóng.");

        closeQuietly(serverSocket);
        serverSocket = null;

        for (ClientHandler handler : handlers) {
            handler.close();
        }
        handlers.clear();
        for (ClientHandler handler : pendingHandlers) {
            // Client đang chờ duyệt: đóng socket để không giữ kết nối treo.
            handler.close();
        }
        pendingHandlers.clear();
        clientsByName.clear();
        groups.clear();
        notifyClientCount();
        notifyGroupCount();

        Thread thread = this.acceptThread;
        this.acceptThread = null;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        this.port = 0;
        log("Server đã dừng (cổng " + stoppedPort + ").");
    }

    /**
     * Vòng lặp chờ client kết nối. Chạy trên luồng nền "server-accept".
     */
    private void acceptLoop() {
        ServerSocket socket = this.serverSocket;
        if (socket == null) {
            return;
        }

        while (running) {
            try {
                Socket clientSocket = socket.accept();
                String clientAddress = describe(clientSocket);
                String clientName = "Client-" + clientCounter.incrementAndGet();
                log("Client " + clientName + " kết nối: " + clientAddress);

                ClientHandler handler = new ClientHandler(clientSocket, this, logger, clientName);
                Thread worker = new Thread(handler, "client-" + clientName);
                worker.setDaemon(true);
                worker.start();

                if (!running) {
                    // Server vừa được dừng trong lúc accept() trả về: đóng kết nối mới.
                    handler.close();
                    break;
                }
            } catch (SocketException e) {
                // ServerSocket bị đóng bởi stop() -> thoát vòng lặp bình thường.
                if (running) {
                    log("Lỗi ServerSocket: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    log("Lỗi khi chờ client kết nối: " + e.getMessage());
                }
            }
        }

        log("Đã kết thúc vòng lặp chờ kết nối.");
    }

    /**
     * Được {@link ClientHandler} gọi sau khi đã mở luồng dữ liệu và gửi tên định danh.
     *
     * @return {@code false} nếu server đã dừng trong lúc client đang kết nối
     */
    boolean registerHandler(ClientHandler handler) {
        if (!running) {
            return false;
        }
        handlers.add(handler);
        clientsByName.put(handler.getClientName(), handler);
        log(handler.getClientName() + " đã tham gia phòng chat. Số client đang kết nối: " + handlers.size());
        notifyClientCount();
        broadcastSystem(handler.getClientName() + " đã tham gia phòng chat.");
        broadcastUserList();
        return true;
    }

    /**
     * Xin ý kiến người quản trị server về một yêu cầu kết nối. Được
     * {@link ClientHandler} gọi <b>trước khi</b> client được cấp tên, đăng ký vào
     * phòng hay nhận bất kỳ thông báo nào.
     *
     * @param handler handler của client đang chờ duyệt
     * @return {@code true} nếu được vào phòng chat
     */
    boolean requestJoin(ClientHandler handler) {
        if (!running) {
            return false;
        }
        pendingHandlers.add(handler);
        try {
            JoinApprover approver = this.joinApprover;
            boolean approved = approver.approve(handler.getClientName(), handler.getClientAddress());
            log((approved ? "Chấp nhận" : "Từ chối") + " yêu cầu kết nối từ "
                    + handler.getClientName() + " (" + handler.getClientAddress() + ").");
            return approved;
        } catch (RuntimeException e) {
            log("Lỗi khi xin ý kiến chấp nhận " + handler.getClientName() + ": " + e.getMessage());
            return false;
        } finally {
            pendingHandlers.remove(handler);
        }
    }

    /**
     * Được {@link ClientHandler} gọi khi client ngắt kết nối hoặc gặp lỗi.
     *
     * <p>Client bị xóa khỏi mọi nhóm; các thành viên còn lại nhận {@code GLEFT}.
     * Nhóm rỗng bị xóa khỏi server.</p>
     */
    void removeHandler(ClientHandler handler) {
        if (!handlers.remove(handler)) {
            return;
        }
        String clientName = handler.getClientName();
        clientsByName.remove(clientName, handler);
        log(clientName + " đã rời phòng chat. Số client đang kết nối: " + handlers.size());
        notifyClientCount();

        leaveAllGroups(clientName);

        if (running) {
            broadcastSystem(clientName + " đã rời phòng chat.");
            broadcastUserList();
        }
    }

    /**
     * Gửi một tin nhắn riêng từ {@code senderName} tới {@code targetName}.
     *
     * <p>Server chỉ gửi cho đúng hai client (người gửi nhận bản echo và người nhận),
     * không phát cho các client khác. Server ghi nhật ký nội dung theo yêu cầu
     * "server thấy tin nhắn".</p>
     *
     * @param senderName tên người gửi do server quản lý
     * @param targetName tên người nhận
     * @param text       nội dung tin nhắn
     */
    void sendPrivateMessage(String senderName, String targetName, String text) {
        String target = (targetName != null) ? targetName.trim() : "";
        if (target.isEmpty()) {
            sendError(senderName, Protocol.ERROR_BAD_REQUEST, "Thiếu tên người nhận.");
            return;
        }
        if (target.equals(senderName)) {
            sendError(senderName, Protocol.ERROR_SELF_TARGET, "Không thể gửi tin riêng cho chính mình.");
            return;
        }

        ClientHandler sender = clientsByName.get(senderName);
        ClientHandler receiver = clientsByName.get(target);
        if (sender == null) {
            return;
        }
        if (receiver == null) {
            sendError(senderName, Protocol.ERROR_TARGET_NOT_FOUND,
                    "Không tìm thấy người nhận " + target + " (có thể đã ngắt kết nối).");
            return;
        }

        String line = Protocol.serverPrivateMessage(senderName, target, text);
        synchronized (broadcastLock) {
            sender.sendLine(line);
            receiver.sendLine(line);
        }
        log("[Riêng] " + senderName + " -> " + target + ": " + text);
    }

    /**
     * Tạo nhóm mới với người tạo và danh sách thành viên được mời.
     *
     * <p>Chỉ kiểm tra số thành viên tối thiểu ({@link Protocol#MIN_GROUP_MEMBERS})
     * tại đây. Sau khi tạo, nhóm vẫn hoạt động khi số thành viên giảm xuống 1-2.</p>
     *
     * @param creatorName tên người tạo nhóm
     * @param invited     tên các thành viên được mời (không cần gồm người tạo)
     */
    void createGroup(String creatorName, List<String> invited) {
        List<String> memberNames = new ArrayList<>();
        memberNames.add(creatorName);
        if (invited != null) {
            for (String name : invited) {
                if (name == null) {
                    continue;
                }
                String trimmed = name.trim();
                if (!trimmed.isEmpty() && !memberNames.contains(trimmed)) {
                    memberNames.add(trimmed);
                }
            }
        }

        synchronized (broadcastLock) {
            if (!running || !clientsByName.containsKey(creatorName)) {
                return;
            }
            for (String name : memberNames) {
                if (!clientsByName.containsKey(name)) {
                    sendError(creatorName, Protocol.ERROR_UNKNOWN_MEMBER,
                            "Thành viên " + name + " không online.");
                    return;
                }
            }
            if (memberNames.size() < Protocol.MIN_GROUP_MEMBERS) {
                sendError(creatorName, Protocol.ERROR_GROUP_TOO_SMALL,
                        "Nhóm cần ít nhất " + Protocol.MIN_GROUP_MEMBERS
                                + " thành viên (hiện có " + memberNames.size() + ").");
                return;
            }
            if (memberNames.size() > MAX_GROUP_MEMBERS) {
                sendError(creatorName, Protocol.ERROR_GROUP_TOO_LARGE,
                        "Nhóm tối đa " + MAX_GROUP_MEMBERS + " thành viên.");
                return;
            }
            if (countGroupsOf(creatorName) >= MAX_GROUPS_PER_CLIENT) {
                sendError(creatorName, Protocol.ERROR_TOO_MANY_GROUPS,
                        "Bạn đã tham gia tối đa " + MAX_GROUPS_PER_CLIENT + " nhóm.");
                return;
            }

            String groupId = "G" + groupCounter.incrementAndGet();
            Group group = new Group(groupId, creatorName, memberNames);
            groups.put(groupId, group);

            String line = Protocol.groupInfoMessage(groupId, memberNames);
            for (String name : memberNames) {
                sendLineTo(name, line);
            }
            log("Tạo nhóm " + groupId + " (người tạo " + creatorName + "): "
                    + String.join(", ", memberNames));
        }
        notifyGroupCount();
    }

    /**
     * Gửi tin nhắn của một thành viên tới tất cả thành viên còn lại của nhóm.
     *
     * @param senderName tên người gửi
     * @param groupId    mã nhóm
     * @param text       nội dung tin nhắn
     */
    void sendGroupMessage(String senderName, String groupId, String text) {
        String id = (groupId != null) ? groupId.trim() : "";
        if (id.isEmpty()) {
            sendError(senderName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm.");
            return;
        }

        Group group = groups.get(id);
        if (group == null) {
            sendError(senderName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!group.contains(senderName)) {
            sendError(senderName, Protocol.ERROR_NOT_MEMBER, "Bạn không phải thành viên nhóm " + id + ".");
            return;
        }

        String line = Protocol.serverGroupMessage(id, senderName, text);
        synchronized (broadcastLock) {
            for (String member : group.snapshotMembers()) {
                sendLineTo(member, line);
            }
        }
        log("[Nhóm " + id + "] " + senderName + ": " + text);
    }

    /**
     * Cho một client chủ động rời nhóm.
     *
     * @param senderName tên client
     * @param groupId    mã nhóm
     */
    void leaveGroup(String senderName, String groupId) {
        String id = (groupId != null) ? groupId.trim() : "";
        if (id.isEmpty()) {
            sendError(senderName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm.");
            return;
        }
        Group group = groups.get(id);
        if (group == null) {
            sendError(senderName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!group.contains(senderName)) {
            sendError(senderName, Protocol.ERROR_NOT_MEMBER, "Bạn không phải thành viên nhóm " + id + ".");
            return;
        }
        removeMemberAndNotify(group, senderName, "đã rời nhóm");
        notifyGroupCount();
    }

    /**
     * Xóa client khỏi tất cả nhóm (dùng khi client mất kết nối).
     */
    private void leaveAllGroups(String clientName) {
        if (groups.isEmpty()) {
            return;
        }
        for (Group group : new ArrayList<>(groups.values())) {
            if (group.contains(clientName)) {
                removeMemberAndNotify(group, clientName, "mất kết nối");
            }
        }
        notifyGroupCount();
    }

    /**
     * Xóa một thành viên khỏi nhóm và thông báo cho các thành viên còn lại.
     * Nhóm rỗng bị xóa khỏi registry.
     */
    private void removeMemberAndNotify(Group group, String memberName, String reason) {
        if (!group.removeMember(memberName)) {
            return;
        }
        if (group.isEmpty()) {
            groups.remove(group.getId(), group);
            log("Nhóm " + group.getId() + " không còn thành viên, đã xóa.");
            return;
        }
        String line = Protocol.groupMemberLeftMessage(group.getId(), memberName, reason);
        sendToNames(group.snapshotMembers(), line);
        log(memberName + " " + reason + " khỏi nhóm " + group.getId()
                + " (còn " + group.size() + " thành viên).");
    }

    /**
     * Gửi danh sách client đang online cho một client (trả lời yêu cầu {@code USERS}).
     *
     * @param handler client cần nhận danh sách
     */
    void sendUserList(ClientHandler handler) {
        synchronized (broadcastLock) {
            handler.sendLine(Protocol.userListMessage(onlineClientNames()));
        }
    }

    /**
     * Phát danh sách client đang online cho tất cả client.
     */
    private void broadcastUserList() {
        broadcastLine(Protocol.userListMessage(onlineClientNames()));
    }

    /**
     * @return tên các client đang kết nối, theo thứ tự tham gia
     */
    public List<String> getOnlineClientNames() {
        List<String> names = new ArrayList<>();
        for (ClientHandler handler : handlers) {
            if (!handler.isClosed()) {
                names.add(handler.getClientName());
            }
        }
        return names;
    }

    private List<String> onlineClientNames() {
        return getOnlineClientNames();
    }

    /**
     * Gửi một dòng tới danh sách client được chỉ định (dùng cho DM và tin nhóm).
     */
    private void sendToNames(Collection<String> names, String line) {
        if (names == null || line == null) {
            return;
        }
        synchronized (broadcastLock) {
            for (String name : names) {
                sendLineTo(name, line);
            }
        }
    }

    /**
     * Gửi một dòng tới đúng một client theo tên; bỏ qua nếu client đã rời.
     */
    private void sendLineTo(String name, String line) {
        ClientHandler handler = clientsByName.get(name);
        if (handler != null) {
            handler.sendLine(line);
        }
    }

    /**
     * Gửi thông báo lỗi tới một client (nếu còn kết nối).
     */
    private void sendError(String name, String code, String message) {
        ClientHandler handler = clientsByName.get(name);
        if (handler != null) {
            handler.sendLine(Protocol.errorMessage(code, message));
        }
    }

    private int countGroupsOf(String clientName) {
        int count = 0;
        for (Group group : groups.values()) {
            if (group.contains(clientName)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Phát một tin nhắn công khai tới tất cả client đang kết nối (kể cả người gửi).
     *
     * @param senderName tên người gửi do server quản lý
     * @param text       nội dung tin nhắn
     */
    void broadcastPublicMessage(String senderName, String text) {
        broadcastLine(Protocol.serverChatMessage(senderName, text));
    }

    /**
     * Phát một thông báo hệ thống tới tất cả client đang kết nối.
     *
     * @param text nội dung thông báo
     */
    void broadcastSystem(String text) {
        broadcastLine(Protocol.systemMessage(text));
    }

    /**
     * Gửi một dòng tới mọi client. Khóa {@code broadcastLock} bảo đảm thứ tự tin
     * nhắn giống nhau ở mọi client; {@link ClientHandler#sendLine(String)} tự bỏ
     * qua client đã đóng nên một client lỗi không ảnh hưởng người khác.
     */
    private void broadcastLine(String line) {
        synchronized (broadcastLock) {
            int failed = 0;
            for (ClientHandler handler : handlers) {
                if (!handler.sendLine(line) && !handler.isClosed()) {
                    failed++;
                }
            }
            if (failed > 0) {
                log("Không gửi được tin nhắn tới " + failed + " client (kết nối đã đóng).");
            }
        }
    }

    private void notifyClientCount() {
        Consumer<Integer> listener = this.clientCountListener;
        try {
            listener.accept(handlers.size());
        } catch (RuntimeException e) {
            log("Lỗi khi cập nhật số client: " + e.getMessage());
        }
    }

    private void notifyGroupCount() {
        Consumer<Integer> listener = this.groupCountListener;
        try {
            listener.accept(groups.size());
        } catch (RuntimeException e) {
            log("Lỗi khi cập nhật số nhóm: " + e.getMessage());
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public int getClientCount() {
        return handlers.size();
    }

    /**
     * @return số nhóm hiện có trên server
     */
    public int getGroupCount() {
        return groups.size();
    }

    /**
     * Mô tả địa chỉ client dạng "ip:port" để ghi nhật ký.
     */
    static String describe(Socket socket) {
        if (socket == null) {
            return "unknown";
        }
        return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    private void log(String message) {
        logger.accept(message);
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Không còn gì để xử lý khi đóng socket.
        }
    }
}
