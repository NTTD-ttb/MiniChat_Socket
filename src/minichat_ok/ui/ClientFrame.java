package minichat_ok.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;

import minichat_ok.client.ChatClient;
import minichat_ok.client.ChatClientListener;
import minichat_ok.common.Protocol;

/**
 * Giao diện Swing của phía Client (giai đoạn 3).
 *
 * <p>Ba tab: <b>Phòng chung</b> (broadcast tới mọi client), <b>Chat riêng</b>
 * (1-1, chỉ người gửi và người nhận thấy) và <b>Nhóm</b> (chỉ thành viên nhóm thấy).
 * Việc kết nối và nhận dữ liệu chạy trên luồng nền; mọi cập nhật Swing đều được
 * chuyển về EDT bằng {@link SwingUtilities#invokeLater(Runnable)}.</p>
 */
@SuppressWarnings("this-escape")
public class ClientFrame extends JFrame implements ChatClientListener {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;
    private static final int MAX_HISTORY_LINES = 300;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Color COLOR_OK = new Color(0, 128, 0);
    private static final Color COLOR_ERROR = new Color(180, 0, 0);
    private static final Color COLOR_WAIT = new Color(180, 120, 0);
    private static final Color COLOR_IDLE = Color.DARK_GRAY;

    // --- Kết nối / trạng thái ---
    private final JTextField hostField = new JTextField(DEFAULT_HOST, 12);
    private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 6);
    private final JButton connectButton = new JButton("Kết nối");
    private final JButton disconnectButton = new JButton("Ngắt kết nối");
    private final JLabel statusLabel = new JLabel("Chưa kết nối");
    private final JTabbedPane tabs = new JTabbedPane();

    // --- Tab phòng chung ---
    private final JTextArea chatArea = new JTextArea();
    private final JTextField inputField = new JTextField();
    private final JButton sendButton = new JButton("Gửi");

    // --- Tab chat riêng ---
    private final DefaultListModel<String> onlineModel = new DefaultListModel<>();
    private final JList<String> onlineList = new JList<>(onlineModel);
    private final JLabel privateHeader = new JLabel("Chọn một client ở danh sách bên trái");
    private final JTextArea privateArea = new JTextArea();
    private final JTextField privateInput = new JTextField();
    private final JButton privateSendButton = new JButton("Gửi riêng");
    private final transient Map<String, List<String>> privateHistory = new LinkedHashMap<>();
    private final transient Map<String, Integer> privateUnread = new LinkedHashMap<>();
    private final transient Set<String> onlineUsers = new LinkedHashSet<>();
    private String currentPrivateTarget;

    // --- Tab nhóm ---
    private final DefaultListModel<GroupItem> groupModel = new DefaultListModel<>();
    private final JList<GroupItem> groupList = new JList<>(groupModel);
    private final JLabel groupHeader = new JLabel("Chưa có nhóm nào");
    private final JTextArea groupArea = new JTextArea();
    private final JTextField groupInput = new JTextField();
    private final JButton groupSendButton = new JButton("Gửi nhóm");
    private final JButton createGroupButton = new JButton("Tạo nhóm");
    private final JButton leaveGroupButton = new JButton("Rời nhóm");
    private final transient Map<String, Set<String>> groupMembers = new LinkedHashMap<>();
    private final transient Map<String, List<String>> groupHistory = new LinkedHashMap<>();
    private final transient Map<String, Integer> groupUnread = new LinkedHashMap<>();
    private String currentGroupId;

    // --- Trạng thái phiên ---
    private transient ChatClient client;
    private boolean connecting;
    private String clientName;
    private volatile boolean serverStopping;
    private int privateTabIndex;
    private int groupTabIndex;

    public ClientFrame() {
        super("MiniChat - Client");
        initComponents();
    }

    /**
     * Một dòng trong danh sách nhóm: mã nhóm + số thành viên hiện tại.
     */
    private static final class GroupItem {

        private final String id;
        private final int memberCount;

        GroupItem(String id, int memberCount) {
            this.id = id;
            this.memberCount = memberCount;
        }

        @Override
        public String toString() {
            return id + " (" + memberCount + " thành viên)";
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof GroupItem item)) {
                return false;
            }
            return id.equals(item.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    private void initComponents() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setContentPane(root);

        JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        connectionPanel.setBorder(BorderFactory.createTitledBorder("Kết nối"));
        connectionPanel.add(new JLabel("IP:"));
        connectionPanel.add(hostField);
        connectionPanel.add(new JLabel("Cổng:"));
        connectionPanel.add(portField);
        connectionPanel.add(connectButton);
        connectionPanel.add(disconnectButton);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        statusPanel.add(new JLabel("Trạng thái:"));
        statusLabel.setForeground(COLOR_IDLE);
        statusPanel.add(statusLabel);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(connectionPanel, BorderLayout.NORTH);
        topPanel.add(statusPanel, BorderLayout.SOUTH);
        root.add(topPanel, BorderLayout.NORTH);

        tabs.addTab("Phòng chung", buildLobbyPanel());
        privateTabIndex = tabs.getTabCount();
        tabs.addTab("Chat riêng", buildPrivatePanel());
        groupTabIndex = tabs.getTabCount();
        tabs.addTab("Nhóm", buildGroupPanel());
        root.add(tabs, BorderLayout.CENTER);

        getRootPane().setDefaultButton(sendButton);

        connectButton.addActionListener(e -> connectToServer());
        disconnectButton.addActionListener(e -> disconnectFromServer());
        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        appendLog("Nhập IP và cổng của Server rồi bấm Kết nối.");
        updateControls();

        setPreferredSize(new Dimension(980, 620));
        setMinimumSize(new Dimension(820, 520));
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildLobbyPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Tin nhắn phòng chung"));
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(8, 0));
        inputPanel.add(new JLabel("Nội dung:"), BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        panel.add(inputPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildPrivatePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        onlineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        onlineList.setVisibleRowCount(18);
        onlineList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        onlineList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onPrivateTargetSelected();
            }
        });
        JScrollPane usersScroll = new JScrollPane(onlineList);
        usersScroll.setBorder(BorderFactory.createTitledBorder("Client đang online"));
        JPanel usersPanel = new JPanel(new BorderLayout());
        usersPanel.add(usersScroll, BorderLayout.CENTER);
        usersPanel.setPreferredSize(new Dimension(210, 0));

        privateArea.setEditable(false);
        privateArea.setLineWrap(true);
        privateArea.setWrapStyleWord(true);
        privateArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        privateHeader.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 4));
        JScrollPane privateScroll = new JScrollPane(privateArea);
        privateScroll.setBorder(BorderFactory.createTitledBorder("Tin nhắn riêng"));

        JPanel privateInputPanel = new JPanel(new BorderLayout(8, 0));
        privateInputPanel.add(new JLabel("Nhắn riêng:"), BorderLayout.WEST);
        privateInputPanel.add(privateInput, BorderLayout.CENTER);
        privateInputPanel.add(privateSendButton, BorderLayout.EAST);

        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.add(privateHeader, BorderLayout.NORTH);
        right.add(privateScroll, BorderLayout.CENTER);
        right.add(privateInputPanel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, usersPanel, right);
        split.setResizeWeight(0.0);
        split.setDividerLocation(210);
        panel.add(split, BorderLayout.CENTER);

        privateSendButton.addActionListener(e -> sendPrivateMessage());
        privateInput.addActionListener(e -> sendPrivateMessage());
        return panel;
    }

    private JPanel buildGroupPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setVisibleRowCount(14);
        groupList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onGroupSelected();
            }
        });
        JScrollPane groupScroll = new JScrollPane(groupList);
        groupScroll.setBorder(BorderFactory.createTitledBorder("Nhóm của bạn"));

        JPanel groupButtons = new JPanel(new GridLayout(2, 1, 4, 4));
        groupButtons.add(createGroupButton);
        groupButtons.add(leaveGroupButton);

        JPanel groupsPanel = new JPanel(new BorderLayout(0, 6));
        groupsPanel.add(groupScroll, BorderLayout.CENTER);
        groupsPanel.add(groupButtons, BorderLayout.SOUTH);
        groupsPanel.setPreferredSize(new Dimension(230, 0));

        groupArea.setEditable(false);
        groupArea.setLineWrap(true);
        groupArea.setWrapStyleWord(true);
        groupArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        groupHeader.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 4));
        JScrollPane groupChatScroll = new JScrollPane(groupArea);
        groupChatScroll.setBorder(BorderFactory.createTitledBorder("Tin nhắn nhóm"));

        JPanel groupInputPanel = new JPanel(new BorderLayout(8, 0));
        groupInputPanel.add(new JLabel("Nhắn nhóm:"), BorderLayout.WEST);
        groupInputPanel.add(groupInput, BorderLayout.CENTER);
        groupInputPanel.add(groupSendButton, BorderLayout.EAST);

        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.add(groupHeader, BorderLayout.NORTH);
        right.add(groupChatScroll, BorderLayout.CENTER);
        right.add(groupInputPanel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, groupsPanel, right);
        split.setResizeWeight(0.0);
        split.setDividerLocation(230);
        panel.add(split, BorderLayout.CENTER);

        createGroupButton.addActionListener(e -> createGroup());
        leaveGroupButton.addActionListener(e -> leaveGroup());
        groupSendButton.addActionListener(e -> sendGroupMessage());
        groupInput.addActionListener(e -> sendGroupMessage());
        return panel;
    }

    private void connectToServer() {
        if (connecting || (client != null && client.isConnected())) {
            return;
        }

        String host = hostField.getText().trim();
        String portText = portField.getText().trim();

        if (host.isEmpty()) {
            showError("Địa chỉ IP không được để trống.");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            showError("Cổng phải là số nguyên trong khoảng 1..65535.");
            return;
        }
        if (port < 1 || port > 65535) {
            showError("Cổng phải nằm trong khoảng 1..65535.");
            return;
        }

        connecting = true;
        clientName = null;
        serverStopping = false;
        updateControls();
        setStatus("Đang chờ Server chấp nhận...", COLOR_WAIT);
        appendLog("--- Đang gửi yêu cầu kết nối tới " + host + ":" + port + " ---");

        Thread worker = new Thread(() -> {
            ChatClient newClient = new ChatClient(this);
            try {
                newClient.connect(host, port);
            } catch (IOException | RuntimeException e) {
                String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    updateControls();
                    setStatus("Server từ chối hoặc không phản hồi", COLOR_ERROR);
                    appendLog("--- Không vào được phòng chat " + host + ":" + port + ": " + reason + " ---");
                    showError("Không vào được phòng chat " + host + ":" + port + ".\n"
                            + "Server có thể đã từ chối yêu cầu kết nối hoặc chưa được khởi động.\n"
                            + "Chi tiết: " + reason);
                });
                return;
            }

            SwingUtilities.invokeLater(() -> {
                client = newClient;
                connecting = false;
                updateControls();
                applyConnectedStatus();
                appendLog("--- Đã kết nối tới server " + host + ":" + port + " ---");
                inputField.requestFocusInWindow();
            });
        }, "client-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void disconnectFromServer() {
        ChatClient current = client;
        client = null;
        if (current != null) {
            current.disconnect();
        }
        connecting = false;
        serverStopping = false;
        resetClientIdentity();
        clearSessionState();
        updateControls();
        setStatus("Đã ngắt kết nối", COLOR_IDLE);
        appendLog("--- Đã ngắt kết nối ---");
    }

    @Override
    public void onChatMessage(String sender, String text) {
        SwingUtilities.invokeLater(() -> appendLog(sender + ": " + text));
    }

    @Override
    public void onSystemMessage(String message) {
        if (message != null && message.contains("Server đang dừng")) {
            serverStopping = true;
        }
        SwingUtilities.invokeLater(() -> appendLog("[Hệ thống] " + message));
    }

    @Override
    public void onNameAssigned(String name) {
        SwingUtilities.invokeLater(() -> {
            clientName = name;
            setTitle("MiniChat - Client [" + name + "]");
            applyConnectedStatus();
        });
    }

    @Override
    public void onConnectionLost(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (client == null) {
                // Người dùng đã chủ động ngắt kết nối.
                return;
            }
            ChatClient lostClient = client;
            client = null;
            lostClient.disconnect();
            connecting = false;
            boolean expectedStop = serverStopping;
            serverStopping = false;
            resetClientIdentity();
            clearSessionState();
            updateControls();
            if (expectedStop) {
                setStatus("Server đã dừng", COLOR_ERROR);
                appendLog("--- Server đã dừng, kết nối đã đóng ---");
            } else {
                setStatus("Mất kết nối tới server", COLOR_ERROR);
                appendLog("--- Mất kết nối tới server: " + reason + " ---");
                showError("Mất kết nối tới server.\nChi tiết: " + reason);
            }
        });
    }

    @Override
    public void onUserList(List<String> users) {
        SwingUtilities.invokeLater(() -> {
            onlineUsers.clear();
            onlineUsers.addAll(users);
            String selected = onlineList.getSelectedValue();
            onlineModel.clear();
            for (String user : users) {
                if (!user.equals(clientName)) {
                    onlineModel.addElement(user);
                }
            }
            if (selected != null && onlineModel.contains(selected)) {
                onlineList.setSelectedValue(selected, true);
            }
            updatePrivateHeader();
            updatePrivateInputState();
        });
    }

    @Override
    public void onPrivateMessage(String sender, String target, String text) {
        SwingUtilities.invokeLater(() -> {
            String other = resolveOtherParty(sender, target);
            if (other == null) {
                return;
            }
            boolean mine = sender.equals(clientName);
            String line = formatLine((mine ? "Tôi: " : sender + ": ") + text);
            addHistoryLine(privateHistory, other, line);
            if (other.equals(currentPrivateTarget)) {
                appendToArea(privateArea, line);
            } else {
                privateUnread.merge(other, 1, Integer::sum);
                updateTabTitles();
            }
        });
    }

    @Override
    public void onGroupCreated(String groupId, List<String> members) {
        SwingUtilities.invokeLater(() -> {
            boolean isNew = !groupMembers.containsKey(groupId);
            groupMembers.put(groupId, new LinkedHashSet<>(members));
            updateGroupItem(groupId, members.size());
            if (isNew) {
                String notice = formatLine("[Hệ thống] Bạn đã được thêm vào nhóm " + groupId
                        + " (" + members.size() + " thành viên): " + String.join(", ", members));
                addHistoryLine(groupHistory, groupId, notice);
            }
            if (currentGroupId == null) {
                selectGroup(groupId);
            }
            if (groupId.equals(currentGroupId)) {
                updateGroupHeader();
            }
            updateTabTitles();
            updateControls();
        });
    }

    @Override
    public void onGroupMessage(String groupId, String sender, String text) {
        SwingUtilities.invokeLater(() -> {
            boolean mine = sender.equals(clientName);
            String line = formatLine((mine ? "Tôi: " : sender + ": ") + text);
            addHistoryLine(groupHistory, groupId, line);
            if (groupId.equals(currentGroupId)) {
                appendToArea(groupArea, line);
            } else {
                groupUnread.merge(groupId, 1, Integer::sum);
                updateTabTitles();
            }
        });
    }

    @Override
    public void onGroupMemberLeft(String groupId, String name, String reason) {
        SwingUtilities.invokeLater(() -> {
            Set<String> members = groupMembers.get(groupId);
            if (members != null) {
                members.remove(name);
                updateGroupItem(groupId, members.size());
            }
            String line = formatLine("[Hệ thống] " + name + " " + reason + " khỏi nhóm " + groupId + ".");
            addHistoryLine(groupHistory, groupId, line);
            if (groupId.equals(currentGroupId)) {
                appendToArea(groupArea, line);
                updateGroupHeader();
            }
        });
    }

    @Override
    public void onError(String code, String message) {
        SwingUtilities.invokeLater(() -> {
            String text = (message == null || message.isBlank()) ? code : message;
            appendLog("[Lỗi] " + text);
            JOptionPane.showMessageDialog(this, text, "Không thực hiện được",
                    JOptionPane.WARNING_MESSAGE);
        });
    }

    private void sendMessage() {
        ChatClient current = client;
        if (current == null || !current.isConnected()) {
            showError("Chưa kết nối tới server. Hãy bấm Kết nối trước.");
            return;
        }
        String text = readInput(inputField);
        if (text == null) {
            return;
        }
        try {
            current.sendMessage(text);
        } catch (IOException | RuntimeException e) {
            String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            appendLog("--- Không gửi được tin nhắn: " + reason + " ---");
            handleSendFailure(reason);
            return;
        }
        // Không hiển thị cục bộ: server phát lại tin nhắn cho mọi client, kể cả người gửi.
        inputField.setText("");
        inputField.requestFocusInWindow();
    }

    private void sendPrivateMessage() {
        ChatClient current = client;
        if (current == null || !current.isConnected()) {
            showError("Chưa kết nối tới server. Hãy bấm Kết nối trước.");
            return;
        }
        String target = currentPrivateTarget;
        if (target == null || target.isBlank()) {
            showError("Hãy chọn một client trong danh sách bên trái để chat riêng.");
            return;
        }
        if (!onlineUsers.contains(target)) {
            showError("Client " + target + " không còn online. Hãy chọn người khác.");
            updatePrivateInputState();
            return;
        }
        String text = readInput(privateInput);
        if (text == null) {
            return;
        }
        try {
            current.sendPrivateMessage(target, text);
        } catch (IOException | RuntimeException e) {
            String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            appendLog("--- Không gửi được tin riêng: " + reason + " ---");
            handleSendFailure(reason);
            return;
        }
        privateInput.setText("");
        privateInput.requestFocusInWindow();
    }

    private void sendGroupMessage() {
        ChatClient current = client;
        if (current == null || !current.isConnected()) {
            showError("Chưa kết nối tới server. Hãy bấm Kết nối trước.");
            return;
        }
        if (currentGroupId == null) {
            showError("Hãy chọn hoặc tạo một nhóm trước.");
            return;
        }
        String text = readInput(groupInput);
        if (text == null) {
            return;
        }
        try {
            current.sendGroupMessage(currentGroupId, text);
        } catch (IOException | RuntimeException e) {
            String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            appendLog("--- Không gửi được tin nhóm: " + reason + " ---");
            handleSendFailure(reason);
            return;
        }
        groupInput.setText("");
        groupInput.requestFocusInWindow();
    }

    /**
     * Đọc và kiểm tra nội dung từ ô nhập; trả về {@code null} nếu không hợp lệ.
     */
    private String readInput(JTextField field) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            field.requestFocusInWindow();
            return null;
        }
        if (text.length() > Protocol.MAX_MESSAGE_LENGTH) {
            showError("Tin nhắn quá dài (" + text.length() + " ký tự). Tối đa "
                    + Protocol.MAX_MESSAGE_LENGTH + " ký tự.");
            return null;
        }
        return text;
    }

    private void handleSendFailure(String reason) {
        ChatClient current = client;
        client = null;
        if (current != null) {
            current.disconnect();
        }
        connecting = false;
        resetClientIdentity();
        clearSessionState();
        updateControls();
        setStatus("Mất kết nối tới server", COLOR_ERROR);
        showError("Không thể gửi tin nhắn, kết nối tới server đã bị ngắt.\nChi tiết: " + reason);
    }

    private void createGroup() {
        ChatClient current = client;
        if (current == null || !current.isConnected()) {
            showError("Chưa kết nối tới server. Hãy bấm Kết nối trước.");
            return;
        }
        int needed = Protocol.MIN_GROUP_MEMBERS - 1;
        List<String> candidates = new ArrayList<>();
        for (String user : onlineUsers) {
            if (!user.equals(clientName)) {
                candidates.add(user);
            }
        }
        if (candidates.size() < needed) {
            showError("Cần ít nhất " + Protocol.MIN_GROUP_MEMBERS + " thành viên (bạn và "
                    + needed + " người khác), nhưng hiện chỉ có " + candidates.size()
                    + " client khác đang online.");
            return;
        }

        DefaultListModel<String> model = new DefaultListModel<>();
        for (String user : candidates) {
            model.addElement(user);
        }
        JList<String> picker = new JList<>(model);
        picker.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        picker.setVisibleRowCount(8);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("Chọn ít nhất " + needed + " client (bạn tự động là thành viên):"),
                BorderLayout.NORTH);
        panel.add(new JScrollPane(picker), BorderLayout.CENTER);

        int choice = JOptionPane.showConfirmDialog(this, panel, "Tạo nhóm",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        List<String> selected = picker.getSelectedValuesList();
        if (selected.size() + 1 < Protocol.MIN_GROUP_MEMBERS) {
            showError("Nhóm cần ít nhất " + Protocol.MIN_GROUP_MEMBERS + " thành viên. Bạn đã chọn "
                    + selected.size() + " client khác.");
            return;
        }
        try {
            current.createGroup(selected);
        } catch (IOException | RuntimeException e) {
            String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            showError("Không tạo được nhóm: " + reason);
        }
    }

    private void leaveGroup() {
        ChatClient current = client;
        if (current == null || !current.isConnected()) {
            showError("Chưa kết nối tới server.");
            return;
        }
        String groupId = currentGroupId;
        if (groupId == null) {
            showError("Hãy chọn một nhóm để rời.");
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc muốn rời nhóm " + groupId + "?", "Rời nhóm",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            current.leaveGroup(groupId);
        } catch (IOException | RuntimeException e) {
            String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            showError("Không rời được nhóm: " + reason);
            return;
        }
        removeGroupLocally(groupId);
    }

    private void onPrivateTargetSelected() {
        String target = onlineList.getSelectedValue();
        if (target == null) {
            return;
        }
        currentPrivateTarget = target;
        privateUnread.remove(target);
        privateInput.setText("");
        renderHistory(privateArea, privateHistory.get(target));
        updatePrivateHeader();
        updatePrivateInputState();
        updateTabTitles();
        privateInput.requestFocusInWindow();
    }

    private void onGroupSelected() {
        GroupItem item = groupList.getSelectedValue();
        if (item == null) {
            currentGroupId = null;
            groupArea.setText("");
            updateGroupHeader();
            updateGroupInputState();
            return;
        }
        currentGroupId = item.id;
        groupUnread.remove(item.id);
        renderHistory(groupArea, groupHistory.get(item.id));
        updateGroupHeader();
        updateGroupInputState();
        updateTabTitles();
        groupInput.requestFocusInWindow();
    }

    private void selectGroup(String groupId) {
        for (int i = 0; i < groupModel.size(); i++) {
            if (groupModel.get(i).id.equals(groupId)) {
                groupList.setSelectedIndex(i);
                return;
            }
        }
    }

    private void removeGroupLocally(String groupId) {
        groupMembers.remove(groupId);
        groupHistory.remove(groupId);
        groupUnread.remove(groupId);
        int index = indexOfGroup(groupId);
        if (index >= 0) {
            groupModel.remove(index);
        }
        if (groupId.equals(currentGroupId)) {
            currentGroupId = null;
            groupList.clearSelection();
            groupArea.setText("");
            updateGroupHeader();
            updateGroupInputState();
        }
        updateTabTitles();
        updateControls();
    }

    private int indexOfGroup(String groupId) {
        for (int i = 0; i < groupModel.size(); i++) {
            if (groupModel.get(i).id.equals(groupId)) {
                return i;
            }
        }
        return -1;
    }

    private void updateGroupItem(String groupId, int memberCount) {
        GroupItem updated = new GroupItem(groupId, memberCount);
        int index = indexOfGroup(groupId);
        if (index >= 0) {
            groupModel.setElementAt(updated, index);
        } else {
            groupModel.addElement(updated);
        }
    }

    private void updatePrivateHeader() {
        if (currentPrivateTarget == null) {
            privateHeader.setText("Chọn một client ở danh sách bên trái");
            return;
        }
        boolean online = onlineUsers.contains(currentPrivateTarget);
        privateHeader.setText("Đang chat riêng với " + currentPrivateTarget
                + (online ? "" : " (offline)"));
    }

    private void updatePrivateInputState() {
        boolean connected = client != null && client.isConnected();
        boolean canSend = connected && currentPrivateTarget != null
                && onlineUsers.contains(currentPrivateTarget);
        privateInput.setEnabled(canSend);
        privateSendButton.setEnabled(canSend);
    }

    private void updateGroupHeader() {
        if (currentGroupId == null) {
            groupHeader.setText(groupModel.isEmpty() ? "Chưa có nhóm nào" : "Chọn một nhóm ở danh sách bên trái");
            groupHeader.setToolTipText(null);
            return;
        }
        Set<String> members = groupMembers.get(currentGroupId);
        int count = (members != null) ? members.size() : 0;
        groupHeader.setText("Nhóm " + currentGroupId + " (" + count + " thành viên)");
        groupHeader.setToolTipText((members != null && !members.isEmpty())
                ? "Thành viên: " + String.join(", ", members)
                : null);
    }

    private void updateGroupInputState() {
        boolean connected = client != null && client.isConnected();
        boolean canSend = connected && currentGroupId != null;
        groupInput.setEnabled(canSend);
        groupSendButton.setEnabled(canSend);
    }

    /**
     * Bật/tắt các thành phần điều khiển theo trạng thái kết nối hiện tại.
     */
    private void updateControls() {
        boolean connected = client != null && client.isConnected();
        boolean editable = !connected && !connecting;

        hostField.setEnabled(editable);
        portField.setEnabled(editable);
        connectButton.setEnabled(editable);
        disconnectButton.setEnabled(connected && !connecting);
        inputField.setEnabled(connected);
        sendButton.setEnabled(connected);
        createGroupButton.setEnabled(connected);
        leaveGroupButton.setEnabled(connected && currentGroupId != null);
        updatePrivateInputState();
        updateGroupInputState();
    }

    private void clearSessionState() {
        onlineUsers.clear();
        onlineModel.clear();
        privateHistory.clear();
        privateUnread.clear();
        currentPrivateTarget = null;
        privateArea.setText("");
        privateInput.setText("");
        privateHeader.setText("Chọn một client ở danh sách bên trái");

        groupMembers.clear();
        groupHistory.clear();
        groupUnread.clear();
        groupModel.clear();
        currentGroupId = null;
        groupArea.setText("");
        groupInput.setText("");
        groupHeader.setText("Chưa có nhóm nào");
        groupHeader.setToolTipText(null);
        updateTabTitles();
        updatePrivateInputState();
        updateGroupInputState();
    }

    private void updateTabTitles() {
        int privateTotal = 0;
        for (int count : privateUnread.values()) {
            privateTotal += count;
        }
        int groupTotal = 0;
        for (int count : groupUnread.values()) {
            groupTotal += count;
        }
        tabs.setTitleAt(privateTabIndex, (privateTotal > 0)
                ? "Chat riêng (" + privateTotal + ")" : "Chat riêng");
        tabs.setTitleAt(groupTabIndex, (groupTotal > 0)
                ? "Nhóm (" + groupTotal + ")" : "Nhóm");
    }

    private String resolveOtherParty(String sender, String target) {
        if (clientName != null && sender.equals(clientName)) {
            return (target == null || target.isBlank()) ? sender : target;
        }
        if (sender == null || sender.isBlank()) {
            return (target == null || target.isBlank()) ? null : target;
        }
        return sender;
    }

    private static void addHistoryLine(Map<String, List<String>> histories, String key, String line) {
        List<String> history = histories.computeIfAbsent(key, k -> new ArrayList<>());
        history.add(line);
        while (history.size() > MAX_HISTORY_LINES) {
            history.remove(0);
        }
    }

    private static String formatLine(String message) {
        return "[" + LocalTime.now().format(TIME_FORMAT) + "] " + message;
    }

    private static void renderHistory(JTextArea area, List<String> history) {
        if (history == null || history.isEmpty()) {
            area.setText("");
        } else {
            area.setText(String.join(System.lineSeparator(), history) + System.lineSeparator());
            area.setCaretPosition(area.getDocument().getLength());
        }
    }

    private static void appendToArea(JTextArea area, String line) {
        area.append(line + System.lineSeparator());
        area.setCaretPosition(area.getDocument().getLength());
    }

    /**
     * Cập nhật nhãn trạng thái theo kết nối hiện tại, kèm tên định danh nếu đã có.
     */
    private void applyConnectedStatus() {
        ChatClient current = client;
        if (current == null || !current.isConnected()) {
            return;
        }
        String name = (clientName != null) ? " (" + clientName + ")" : "";
        setStatus("Đã kết nối - " + current.getHost() + ":" + current.getPort() + name, COLOR_OK);
    }

    /**
     * Xóa tên định danh và tiêu đề khi không còn kết nối.
     */
    private void resetClientIdentity() {
        clientName = null;
        setTitle("MiniChat - Client");
    }

    private void shutdown() {
        ChatClient current = client;
        client = null;
        if (current != null) {
            current.disconnect();
        }
        dispose();
        System.exit(0);
    }

    /**
     * Ghi nhật ký vào tab phòng chung, an toàn với Swing: luôn thực hiện trên EDT.
     */
    private void appendLog(String message) {
        String line = formatLine(message) + System.lineSeparator();
        if (SwingUtilities.isEventDispatchThread()) {
            appendLogOnEdt(line);
        } else {
            SwingUtilities.invokeLater(() -> appendLogOnEdt(line));
        }
    }

    private void appendLogOnEdt(String line) {
        chatArea.append(line);
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Lỗi", JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) {
                // Dùng giao diện mặc định của Java nếu không đổi được Look and Feel.
            }
            new ClientFrame().setVisible(true);
        });
    }
}
