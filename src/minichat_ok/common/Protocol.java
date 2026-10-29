package minichat_ok.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Định nghĩa giao thức văn bản dùng chung cho cả Server và Client (giai đoạn 3).
 *
 * <p>Mỗi khung tin là <b>một dòng</b> mã hóa UTF-8, kết thúc bằng đúng một ký tự
 * {@code '\n'}. Hai bên đều dùng {@link java.io.BufferedReader#readLine()} để đọc
 * nên chấp nhận được cả {@code '\n'} và {@code "\r\n"}; khi gửi luôn dùng
 * {@code line + "\n"} (không dùng {@code println()} để tránh hệ điều hành tự đổi
 * thành {@code "\r\n"}).</p>
 *
 * <p>Các loại dòng:</p>
 * <ul>
 *   <li>{@code MSG|<noi dung>} — Client gửi tin nhắn công khai lên Server.
 *       Server tự gán tên người gửi nên Client không thể giả mạo tên.</li>
 *   <li>{@code MSG|<ten nguoi gui>|<noi dung>} — Server phát tin nhắn công khai
 *       tới mọi Client.</li>
 *   <li>{@code NAME|<ten duoc cap>} — Server thông báo tên định danh cho Client
 *       ngay sau khi kết nối.</li>
 *   <li>{@code SYS|<thong bao>} — Server thông báo hệ thống (tham gia, rời phòng,
 *       server dừng...).</li>
 *   <li>{@code PRIV|nguoi-nhan|noi dung} — Client gửi tin nhắn riêng 1-1.</li>
 *   <li>{@code PRIV|nguoi-gui|nguoi-nhan|noi dung} — Server gửi tin nhắn riêng
 *       cho cả người gửi (echo) và người nhận.</li>
 *   <li>{@code USERS|a,b,c} — Danh sách client đang online do Server gửi xuống;
 *       Client gửi {@code USERS} (không có dữ liệu) để yêu cầu danh sách.</li>
 *   <li>{@code GROUP|ma-nhom|ds-thanh-vien} — Server thông báo nhóm cho thành viên.</li>
 *   <li>{@code GMSG|ma-nhom|noi dung} — Client gửi tin nhắn nhóm; Server gửi xuống
 *       dạng {@code GMSG|ma-nhom|nguoi-gui|noi dung}.</li>
 *   <li>{@code GROUP_CREATE|ds-thanh-vien} — Client yêu cầu tạo nhóm.</li>
 *   <li>{@code GLEAVE|ma-nhom} — Client yêu cầu rời nhóm.</li>
 *   <li>{@code GLEFT|ma-nhom|ten|ly-do} — Server báo một thành viên đã rời nhóm.</li>
 *   <li>{@code ERR|ma-loi|thong-diep} — Server từ chối yêu cầu và gửi mã lỗi.</li>
 * </ul>
 *
 * <p>Dấu {@code '|'} chỉ dùng làm dấu phân cách ở các vị trí cố định. Nội dung tin
 * nhắn <b>được phép chứa</b> dấu {@code '|'}: phía Server lấy nội dung bằng
 * {@link #clientChatText(String)} (mọi thứ sau tiền tố {@code "MSG|"}), phía Client
 * tách bằng {@link #fields(String)} (giới hạn 3 phần tử) hoặc
 * {@link #fields(String, int)} (giới hạn 4 phần tử cho {@code PRIV}/{@code GMSG})
 * nên phần nội dung giữ nguyên các dấu {@code '|'} còn lại.</p>
 *
 * <p>Ký tự xuống dòng trong nội dung bị thay bằng dấu cách để một tin nhắn không
 * bao giờ bị tách thành nhiều dòng giao thức.</p>
 */
public final class Protocol {

    /** Dấu phân cách giữa các trường của một dòng. */
    public static final String SEPARATOR = "|";

    /** Loại dòng cấp tên định danh cho client. */
    public static final String TYPE_NAME = "NAME";

    /** Loại dòng tin nhắn công khai. */
    public static final String TYPE_CHAT = "MSG";

    /** Loại dòng thông báo hệ thống. */
    public static final String TYPE_SYSTEM = "SYS";

    /** Loại dòng danh sách client đang online (Server gửi xuống). */
    public static final String TYPE_USERS = "USERS";

    /** Loại dòng tin nhắn riêng giữa hai client. */
    public static final String TYPE_PRIVATE = "PRIV";

    /** Loại dòng thông tin nhóm (Server gửi xuống khi tạo nhóm). */
    public static final String TYPE_GROUP = "GROUP";

    /** Loại dòng tin nhắn trong nhóm. */
    public static final String TYPE_GROUP_MESSAGE = "GMSG";

    /** Loại dòng yêu cầu tạo nhóm (Client gửi lên). */
    public static final String TYPE_GROUP_CREATE = "GROUP_CREATE";

    /** Loại dòng yêu cầu rời nhóm (Client gửi lên). */
    public static final String TYPE_GROUP_LEAVE = "GLEAVE";

    /** Loại dòng báo một thành viên đã rời nhóm (Server gửi xuống). */
    public static final String TYPE_GROUP_LEFT = "GLEFT";

    /** Loại dòng báo lỗi/yêu cầu bị từ chối. */
    public static final String TYPE_ERROR = "ERR";

    /** Loại dòng yêu cầu tham gia phòng chat (Client gửi lên ngay sau khi mở TCP). */
    public static final String TYPE_JOIN = "JOIN";

    /** Loại dòng Server chấp nhận yêu cầu tham gia, kèm tên định danh được cấp. */
    public static final String TYPE_JOIN_OK = "JOIN_OK";

    /** Loại dòng Server từ chối yêu cầu tham gia, kèm lý do. */
    public static final String TYPE_JOIN_DENY = "JOIN_DENY";

    /** Số thành viên tối thiểu của một nhóm <b>tại thời điểm tạo</b>. */
    public static final int MIN_GROUP_MEMBERS = 3;

    /** Mã lỗi: không tìm thấy người nhận. */
    public static final String ERROR_TARGET_NOT_FOUND = "TARGET_NOT_FOUND";

    /** Mã lỗi: chọn chính mình làm người nhận. */
    public static final String ERROR_SELF_TARGET = "SELF_TARGET";

    /** Mã lỗi: thành viên nhóm không tồn tại/không online. */
    public static final String ERROR_UNKNOWN_MEMBER = "UNKNOWN_MEMBER";

    /** Mã lỗi: nhóm không đủ số thành viên tối thiểu khi tạo. */
    public static final String ERROR_GROUP_TOO_SMALL = "GROUP_TOO_SMALL";

    /** Mã lỗi: nhóm quá nhiều thành viên. */
    public static final String ERROR_GROUP_TOO_LARGE = "GROUP_TOO_LARGE";

    /** Mã lỗi: nhóm không tồn tại. */
    public static final String ERROR_GROUP_NOT_FOUND = "GROUP_NOT_FOUND";

    /** Mã lỗi: người gửi không phải thành viên nhóm. */
    public static final String ERROR_NOT_MEMBER = "NOT_MEMBER";

    /** Mã lỗi: một client tham gia quá nhiều nhóm. */
    public static final String ERROR_TOO_MANY_GROUPS = "TOO_MANY_GROUPS";

    /** Mã lỗi: tin nhắn quá dài. */
    public static final String ERROR_MESSAGE_TOO_LONG = "MESSAGE_TOO_LONG";

    /** Mã lỗi: dòng giao thức không hợp lệ. */
    public static final String ERROR_BAD_REQUEST = "BAD_REQUEST";

    /** Mã lỗi: server (người quản trị) từ chối cho client vào phòng. */
    public static final String ERROR_JOIN_REJECTED = "JOIN_REJECTED";

    /** Mã lỗi: client không gửi yêu cầu tham gia đúng hạn. */
    public static final String ERROR_JOIN_TIMEOUT = "JOIN_TIMEOUT";

    /** Độ dài tối đa của nội dung một tin nhắn (ký tự). */
    public static final int MAX_MESSAGE_LENGTH = 4000;

    private Protocol() {
        // Lớp tiện ích: không cho phép khởi tạo.
    }

    /**
     * Tạo dòng thông báo tên định danh do Server cấp.
     *
     * @param clientName tên do Server cấp, ví dụ {@code "Client-1"}
     * @return dòng dạng {@code NAME|Client-1}
     */
    public static String nameMessage(String clientName) {
        return TYPE_NAME + SEPARATOR + sanitize(clientName);
    }

    /**
     * Tạo dòng tin nhắn công khai phía Client gửi lên Server.
     *
     * @param text nội dung tin nhắn
     * @return dòng dạng {@code MSG|Xin chào}
     */
    public static String clientChatMessage(String text) {
        return TYPE_CHAT + SEPARATOR + sanitize(text);
    }

    /**
     * Tạo dòng tin nhắn công khai phía Server phát xuống Client.
     *
     * @param senderName tên người gửi do Server quản lý
     * @param text       nội dung tin nhắn
     * @return dòng dạng {@code MSG|Client-1|Xin chào}
     */
    public static String serverChatMessage(String senderName, String text) {
        return TYPE_CHAT + SEPARATOR + sanitize(senderName) + SEPARATOR + sanitize(text);
    }

    /**
     * Tạo dòng thông báo hệ thống.
     *
     * @param text nội dung thông báo
     * @return dòng dạng {@code SYS|Client-2 đã tham gia phòng chat.}
     */
    public static String systemMessage(String text) {
        return TYPE_SYSTEM + SEPARATOR + sanitize(text);
    }

    /**
     * Tạo dòng danh sách client đang online.
     *
     * @param names tên các client, theo thứ tự tham gia
     * @return dòng dạng {@code USERS|Client-1,Client-2}
     */
    public static String userListMessage(Collection<String> names) {
        return TYPE_USERS + SEPARATOR + joinNames(names);
    }

    /**
     * Tạo dòng yêu cầu gửi tin nhắn riêng (Client gửi lên Server).
     *
     * @param targetName tên người nhận
     * @param text       nội dung tin nhắn
     * @return dòng dạng {@code PRIV|Client-2|Xin chào}
     */
    public static String privateMessage(String targetName, String text) {
        return TYPE_PRIVATE + SEPARATOR + sanitize(targetName) + SEPARATOR + sanitize(text);
    }

    /**
     * Tạo dòng tin nhắn riêng phía Server gửi xuống.
     *
     * <p>Server gửi cùng một dòng cho cả người gửi (echo) và người nhận để hai bên
     * thấy nội dung giống nhau.</p>
     *
     * @param senderName tên người gửi do Server quản lý
     * @param targetName tên người nhận
     * @param text       nội dung tin nhắn
     * @return dòng dạng {@code PRIV|Client-1|Client-2|Xin chào}
     */
    public static String serverPrivateMessage(String senderName, String targetName, String text) {
        return TYPE_PRIVATE + SEPARATOR + sanitize(senderName) + SEPARATOR
                + sanitize(targetName) + SEPARATOR + sanitize(text);
    }

    /**
     * Tạo dòng yêu cầu tạo nhóm (Client gửi lên Server).
     *
     * @param memberNames tên các thành viên được mời (không gồm người tạo)
     * @return dòng dạng {@code GROUP_CREATE|Client-2,Client-3}
     */
    public static String groupCreateMessage(Collection<String> memberNames) {
        return TYPE_GROUP_CREATE + SEPARATOR + joinNames(memberNames);
    }

    /**
     * Tạo dòng thông tin nhóm phía Server gửi xuống các thành viên.
     *
     * @param groupId     mã nhóm, ví dụ {@code "G1"}
     * @param memberNames danh sách thành viên (gồm cả người tạo)
     * @return dòng dạng {@code GROUP|G1|Client-1,Client-2,Client-3}
     */
    public static String groupInfoMessage(String groupId, Collection<String> memberNames) {
        return TYPE_GROUP + SEPARATOR + sanitize(groupId) + SEPARATOR + joinNames(memberNames);
    }

    /**
     * Tạo dòng yêu cầu gửi tin nhắn nhóm (Client gửi lên Server).
     *
     * @param groupId mã nhóm
     * @param text    nội dung tin nhắn
     * @return dòng dạng {@code GMSG|G1|Họp lúc 8h}
     */
    public static String groupChatMessage(String groupId, String text) {
        return TYPE_GROUP_MESSAGE + SEPARATOR + sanitize(groupId) + SEPARATOR + sanitize(text);
    }

    /**
     * Tạo dòng tin nhắn nhóm phía Server gửi xuống các thành viên.
     *
     * @param groupId    mã nhóm
     * @param senderName tên người gửi do Server quản lý
     * @param text       nội dung tin nhắn
     * @return dòng dạng {@code GMSG|G1|Client-1|Họp lúc 8h}
     */
    public static String serverGroupMessage(String groupId, String senderName, String text) {
        return TYPE_GROUP_MESSAGE + SEPARATOR + sanitize(groupId) + SEPARATOR
                + sanitize(senderName) + SEPARATOR + sanitize(text);
    }

    /**
     * Tạo dòng yêu cầu rời nhóm (Client gửi lên Server).
     *
     * @param groupId mã nhóm
     * @return dòng dạng {@code GLEAVE|G1}
     */
    public static String groupLeaveMessage(String groupId) {
        return TYPE_GROUP_LEAVE + SEPARATOR + sanitize(groupId);
    }

    /**
     * Tạo dòng báo một thành viên đã rời nhóm.
     *
     * @param groupId mã nhóm
     * @param name    tên thành viên đã rời
     * @param reason  lý do (ví dụ "đã rời nhóm", "mất kết nối")
     * @return dòng dạng {@code GLEFT|G1|Client-2|đã rời nhóm}
     */
    public static String groupMemberLeftMessage(String groupId, String name, String reason) {
        return TYPE_GROUP_LEFT + SEPARATOR + sanitize(groupId) + SEPARATOR
                + sanitize(name) + SEPARATOR + sanitize(reason);
    }

    /**
     * Tạo dòng báo lỗi/yêu cầu bị từ chối.
     *
     * @param code    mã lỗi, ví dụ {@link #ERROR_TARGET_NOT_FOUND}
     * @param message thông điệp tiếng Việt dễ hiểu cho người dùng
     * @return dòng dạng {@code ERR|TARGET_NOT_FOUND|Không tìm thấy người nhận Client-9}
     */
    public static String errorMessage(String code, String message) {
        return TYPE_ERROR + SEPARATOR + sanitize(code) + SEPARATOR + sanitize(message);
    }

    /**
     * Tạo dòng yêu cầu tham gia phòng chat (Client gửi lên).
     *
     * <p>Dòng này không có dữ liệu nên không kèm dấu phân cách: {@code JOIN}.</p>
     *
     * @return dòng {@code JOIN}
     */
    public static String joinRequestMessage() {
        return TYPE_JOIN;
    }

    /**
     * Tạo dòng Server chấp nhận yêu cầu tham gia.
     *
     * @param clientName tên định danh do Server cấp
     * @return dòng dạng {@code JOIN_OK|Client-1}
     */
    public static String joinOkMessage(String clientName) {
        return TYPE_JOIN_OK + SEPARATOR + sanitize(clientName);
    }

    /**
     * Tạo dòng Server từ chối yêu cầu tham gia.
     *
     * @param reason lý do từ chối, ví dụ {@code "Quản trị viên đã từ chối."}
     * @return dòng dạng {@code JOIN_DENY|Quản trị viên đã từ chối.}
     */
    public static String joinDenyMessage(String reason) {
        return TYPE_JOIN_DENY + SEPARATOR + sanitize(reason);
    }

    /**
     * Lấy loại của một dòng (phần trước dấu {@code '|'} đầu tiên).
     *
     * @param line dòng nhận được, có thể {@code null}
     * @return loại dòng ({@code NAME}, {@code MSG}, {@code SYS}...) hoặc chuỗi rỗng
     */
    public static String typeOf(String line) {
        if (line == null) {
            return "";
        }
        int index = line.indexOf(SEPARATOR);
        String type = (index >= 0) ? line.substring(0, index) : line;
        return type.trim();
    }

    /**
     * Kiểm tra một dòng có thuộc loại cho trước hay không.
     *
     * @param line dòng cần kiểm tra
     * @param type loại mong muốn, ví dụ {@link #TYPE_CHAT}
     * @return {@code true} nếu khớp
     */
    public static boolean isType(String line, String type) {
        return typeOf(line).equals(type);
    }

    /**
     * Lấy toàn bộ phần sau dấu {@code '|'} đầu tiên.
     *
     * <p>Dùng cho {@code NAME} và {@code SYS}. Với {@code MSG} phía Client gửi lên,
     * đây cũng chính là nội dung tin nhắn (giữ nguyên dấu {@code '|'} bên trong).</p>
     *
     * @param line dòng nhận được, có thể {@code null}
     * @return phần sau dấu phân cách đầu tiên, hoặc chuỗi rỗng nếu không có
     */
    public static String payload(String line) {
        if (line == null) {
            return "";
        }
        int index = line.indexOf(SEPARATOR);
        return (index >= 0) ? line.substring(index + 1) : "";
    }

    /**
     * Lấy nội dung tin nhắn từ dòng {@code MSG|<noi dung>} do Client gửi lên.
     *
     * <p>Khác với {@link #fields(String)}: nội dung được giữ nguyên, kể cả khi có
     * chứa dấu {@code '|'}.</p>
     *
     * @param line dòng nhận được từ client
     * @return nội dung tin nhắn (có thể rỗng nếu dòng không đúng định dạng)
     */
    public static String clientChatText(String line) {
        return payload(line);
    }

    /**
     * Tách một dòng thành tối đa 3 trường theo dấu {@code '|'}.
     *
     * <p>Dùng cho các dòng Server gửi xuống: {@code MSG|sender|noi dung} cho kết quả
     * {@code [MSG, sender, noi dung]}, phần nội dung giữ nguyên mọi dấu {@code '|'}.</p>
     *
     * @param line dòng cần tách, có thể {@code null}
     * @return mảng trường; mảng rỗng nếu {@code line} là {@code null}
     */
    public static String[] fields(String line) {
        if (line == null) {
            return new String[0];
        }
        return line.split("\\|", 3);
    }

    /**
     * Tách một dòng thành tối đa {@code limit} trường theo dấu {@code '|'}.
     *
     * <p>Dùng cho các dòng có nhiều trường cố định như {@code PRIV|sender|target|noi dung}
     * (limit = 4) hay {@code GMSG|gid|sender|noi dung} (limit = 4): trường cuối cùng
     * giữ nguyên mọi dấu {@code '|'} còn lại của nội dung.</p>
     *
     * @param line  dòng cần tách, có thể {@code null}
     * @param limit số trường tối đa (phải &gt; 0)
     * @return mảng trường; mảng rỗng nếu {@code line} là {@code null}
     * @throws IllegalArgumentException nếu {@code limit} không dương
     */
    public static String[] fields(String line, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit phải lớn hơn 0");
        }
        if (line == null) {
            return new String[0];
        }
        return line.split("\\|", limit);
    }

    /**
     * Nối danh sách tên thành một chuỗi phân tách bằng dấu phẩy.
     *
     * @param names danh sách tên, có thể {@code null}
     * @return chuỗi dạng {@code Client-1,Client-2} (bỏ qua phần tử rỗng)
     */
    public static String joinNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(name.trim());
        }
        return builder.toString();
    }

    /**
     * Phân tích danh sách tên phân tách bằng dấu phẩy (giữ thứ tự, bỏ trùng lặp).
     *
     * @param csv chuỗi dạng {@code Client-2,Client-3}, có thể {@code null}
     * @return danh sách tên; rỗng nếu không có tên hợp lệ
     */
    public static List<String> parseNameList(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return result;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                unique.add(name);
            }
        }
        result.addAll(unique);
        return result;
    }

    /**
     * Loại bỏ ký tự xuống dòng khỏi nội dung để không phá vỡ khung tin theo dòng.
     */
    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ');
    }
}
