package minichat_ok.client;

import java.util.List;

/**
 * Nơi nhận sự kiện từ {@link ChatClient} (giai đoạn 3).
 *
 * <p>Tất cả phương thức đều có cài đặt mặc định rỗng nên lớp giao diện chỉ cần
 * ghi đè những sự kiện mình quan tâm. Các phương thức được gọi trên luồng nhận
 * dữ liệu của client ({@code client-receiver}); lớp giao diện phải tự chuyển cập
 * nhật Swing về EDT bằng {@code SwingUtilities.invokeLater(...)}.</p>
 */
public interface ChatClientListener {

    /**
     * Nhận tin nhắn phòng chung (kể cả bản echo của chính mình).
     *
     * @param sender tên người gửi
     * @param text   nội dung
     */
    default void onChatMessage(String sender, String text) {
        // Không quan tâm.
    }

    /**
     * Nhận thông báo hệ thống (tham gia/rời phòng, server dừng...).
     *
     * @param message nội dung thông báo
     */
    default void onSystemMessage(String message) {
        // Không quan tâm.
    }

    /**
     * Server đã cấp tên định danh cho client này.
     *
     * @param name tên do server cấp, ví dụ {@code "Client-1"}
     */
    default void onNameAssigned(String name) {
        // Không quan tâm.
    }

    /**
     * Kết nối bị mất ngoài ý muốn (server dừng hoặc đường truyền đóng).
     *
     * @param reason mô tả lý do
     */
    default void onConnectionLost(String reason) {
        // Không quan tâm.
    }

    /**
     * Danh sách client đang online vừa thay đổi.
     *
     * @param users tên các client đang kết nối (gồm cả chính mình)
     */
    default void onUserList(List<String> users) {
        // Không quan tâm.
    }

    /**
     * Nhận tin nhắn riêng 1-1 (người gửi và người nhận đều nhận được bản echo).
     *
     * @param sender tên người gửi
     * @param target tên người nhận
     * @param text   nội dung
     */
    default void onPrivateMessage(String sender, String target, String text) {
        // Không quan tâm.
    }

    /**
     * Client này vừa được thêm vào một nhóm (do chính mình tạo hoặc được mời).
     *
     * @param groupId mã nhóm, ví dụ {@code "G1"}
     * @param members danh sách thành viên, gồm cả chính mình
     */
    default void onGroupCreated(String groupId, List<String> members) {
        // Không quan tâm.
    }

    /**
     * Nhận tin nhắn trong nhóm.
     *
     * @param groupId mã nhóm
     * @param sender  tên người gửi
     * @param text    nội dung
     */
    default void onGroupMessage(String groupId, String sender, String text) {
        // Không quan tâm.
    }

    /**
     * Một thành viên đã rời nhóm (chủ động rời hoặc mất kết nối).
     *
     * @param groupId mã nhóm
     * @param name    tên thành viên đã rời
     * @param reason  lý do, ví dụ {@code "đã rời nhóm"} hoặc {@code "mất kết nối"}
     */
    default void onGroupMemberLeft(String groupId, String name, String reason) {
        // Không quan tâm.
    }

    /**
     * Yêu cầu vừa gửi bị server từ chối.
     *
     * @param code    mã lỗi, ví dụ {@code "TARGET_NOT_FOUND"}
     * @param message thông điệp tiếng Việt do server gửi
     */
    default void onError(String code, String message) {
        // Không quan tâm.
    }
}
