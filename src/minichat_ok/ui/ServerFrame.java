package minichat_ok.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;

import minichat_ok.server.ChatServer;

/**
 * Giao diện Swing của phía Server.
 *
 * <p>Cho phép nhập cổng, khởi động/dừng server và theo dõi nhật ký kết nối.
 * Mọi cập nhật giao diện từ luồng nền đều được chuyển về EDT.</p>
 */
@SuppressWarnings("this-escape")
public class ServerFrame extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_PORT = 5000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Color COLOR_RUNNING = new Color(0, 128, 0);
    private static final Color COLOR_STOPPED = new Color(180, 0, 0);

    private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 6);
    private final JButton startButton = new JButton("Khởi động");
    private final JButton stopButton = new JButton("Dừng");
    private final JLabel statusLabel = new JLabel("Chưa khởi động");
    private final JTextArea logArea = new JTextArea();

    private transient ChatServer server;

    public ServerFrame() {
        super("MiniChat - Server");
        initComponents();
    }

    private void initComponents() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        controlPanel.setBorder(BorderFactory.createTitledBorder("Điều khiển Server"));
        controlPanel.add(new JLabel("Cổng:"));
        controlPanel.add(portField);
        controlPanel.add(startButton);
        controlPanel.add(stopButton);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        statusPanel.add(new JLabel("Trạng thái:"));
        statusLabel.setForeground(COLOR_STOPPED);
        statusPanel.add(statusLabel);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(controlPanel, BorderLayout.NORTH);
        topPanel.add(statusPanel, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Nhật ký kết nối"));
        add(scrollPane, BorderLayout.CENTER);

        stopButton.setEnabled(false);

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        setPreferredSize(new Dimension(680, 460));
        setMinimumSize(new Dimension(540, 360));
        pack();
        setLocationRelativeTo(null);
    }

    private void startServer() {
        String portText = portField.getText().trim();
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

        ChatServer newServer = new ChatServer(this::appendLog);
        newServer.setClientCountListener(count -> refreshStatus());
        newServer.setGroupCountListener(count -> refreshStatus());
        newServer.setJoinApprover(this::askJoinApproval);
        try {
            newServer.start(port);
        } catch (IOException | RuntimeException e) {
            appendLog("Không thể khởi động Server trên cổng " + port + ": " + e.getMessage());
            showError("Không thể khởi động Server trên cổng " + port + ".\n"
                    + "Cổng có thể đang bị chương trình khác sử dụng.\n"
                    + "Chi tiết: " + e.getMessage());
            return;
        }

        server = newServer;
        portField.setEnabled(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        setStatus("Đang chạy - cổng " + port + " - 0 client - 0 nhóm", COLOR_RUNNING);
        setTitle("MiniChat - Server [cổng " + port + "]");
    }

    /**
     * Được gọi từ luồng server khi số client hoặc số nhóm thay đổi; luôn cập nhật trên EDT.
     */
    private void refreshStatus() {
        SwingUtilities.invokeLater(() -> {
            ChatServer current = server;
            if (current == null || !current.isRunning()) {
                return;
            }
            setStatus("Đang chạy - cổng " + current.getPort() + " - " + current.getClientCount()
                    + " client - " + current.getGroupCount() + " nhóm", COLOR_RUNNING);
        });
    }

    /**
     * Hỏi người quản trị Server có cho client vào phòng hay không.
     *
     * <p>Phương thức được gọi từ luồng xử lý client ({@code client-Client-N}), không
     * phải EDT, nên dùng {@link SwingUtilities#invokeAndWait(Runnable)} để hiển thị
     * hộp thoại trên EDT và chờ kết quả.</p>
     *
     * @param clientName    tên định danh Server dự kiến cấp
     * @param clientAddress địa chỉ {@code ip:port} của client
     * @return {@code true} nếu chấp nhận yêu cầu kết nối
     */
    private boolean askJoinApproval(String clientName, String clientAddress) {
        if (!isVisible()) {
            appendLog("Cửa sổ Server đang ẩn nên không thể hỏi: từ chối "
                    + clientName + " (" + clientAddress + ").");
            return false;
        }

        final boolean[] approved = {false};
        try {
            SwingUtilities.invokeAndWait(() -> {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Client " + clientName + " (" + clientAddress + ") yêu cầu kết nối.\n"
                                + "Bạn có cho client này vào phòng chat không?",
                        "Yêu cầu kết nối",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                approved[0] = (choice == JOptionPane.YES_OPTION);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.lang.reflect.InvocationTargetException e) {
            appendLog("Lỗi khi hiển thị hộp thoại chấp nhận: " + e.getCause());
            return false;
        }
        return approved[0];
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        portField.setEnabled(true);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        setStatus("Đã dừng", COLOR_STOPPED);
        setTitle("MiniChat - Server");
    }

    private void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        dispose();
        System.exit(0);
    }

    /**
     * Ghi nhật ký an toàn với Swing: luôn thực hiện trên EDT.
     */
    private void appendLog(String message) {
        String line = "[" + LocalTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
        if (SwingUtilities.isEventDispatchThread()) {
            appendLogOnEdt(line);
        } else {
            SwingUtilities.invokeLater(() -> appendLogOnEdt(line));
        }
    }

    private void appendLogOnEdt(String line) {
        logArea.append(line);
        logArea.setCaretPosition(logArea.getDocument().getLength());
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
            new ServerFrame().setVisible(true);
        });
    }
}
