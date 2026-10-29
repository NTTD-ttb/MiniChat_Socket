import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import minichat_ok.client.ChatClient;
import minichat_ok.client.ChatClientListener;
import minichat_ok.server.ChatServer;

/** Kiểm thử tạm: server chỉ cho client vào phòng khi approver đồng ý. */
public class TestJoin {

    public static void main(String[] args) throws Exception {
        int port = 5011;
        ChatServer server = new ChatServer(message -> { });
        AtomicBoolean allow = new AtomicBoolean(true);
        server.setJoinApprover((clientName, clientAddress) -> allow.get());
        server.start(port);

        ChatClient accepted = new ChatClient(new ChatClientListener() { });
        accepted.connect("127.0.0.1", port);
        String acceptedName = accepted.getClientName();
        System.out.println("ACCEPT: connected=" + accepted.isConnected()
                + " name=" + acceptedName + " serverCount=" + server.getClientCount());
        accepted.disconnect();
        Thread.sleep(300);

        ChatClient denied = new ChatClient(new ChatClientListener() { });
        allow.set(false);
        boolean rejected = false;
        String reason = "";
        try {
            denied.connect("127.0.0.1", port);
        } catch (IOException e) {
            rejected = true;
            reason = e.getMessage();
        }
        Thread.sleep(300);
        System.out.println("DENY: rejected=" + rejected + " reason=" + reason
                + " connected=" + denied.isConnected() + " serverCount=" + server.getClientCount());

        server.stop();
        boolean ok = rejected && "Client-1".equals(acceptedName) && !denied.isConnected()
                && server.getClientCount() == 0;
        System.out.println(ok ? "TEST OK" : "TEST FAIL");
    }
}
