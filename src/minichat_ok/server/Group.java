package minichat_ok.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Một nhóm chat phía Server (giai đoạn 3).
 *
 * <p>Nhóm được xác định bằng {@code id} (ví dụ {@code "G1"}) và giữ tập tên thành viên.
 * Tập này dùng {@link CopyOnWriteArraySet} để một luồng đang phát tin nhắn vẫn có thể
 * duyệt an toàn trong khi luồng khác thêm/xóa thành viên.</p>
 *
 * <p>Quy tắc nghiệp vụ: chỉ kiểm tra số thành viên tối thiểu
 * ({@link minichat_ok.common.Protocol#MIN_GROUP_MEMBERS}) <b>khi tạo nhóm</b>. Sau khi
 * tạo, nhóm vẫn hoạt động dù chỉ còn 1 hoặc 2 thành viên; nhóm chỉ bị xóa khi không
 * còn thành viên nào.</p>
 */
public final class Group {

    private final String id;
    private final String owner;
    private final Set<String> members;

    /**
     * @param id           mã nhóm do Server sinh, ví dụ {@code "G1"}
     * @param owner        tên người tạo nhóm (chỉ để ghi nhật ký, không có đặc quyền)
     * @param memberNames  danh sách thành viên ban đầu, gồm cả người tạo
     */
    public Group(String id, String owner, Collection<String> memberNames) {
        this.id = id;
        this.owner = owner;
        this.members = new CopyOnWriteArraySet<>(memberNames);
    }

    public String getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    /**
     * @return {@code true} nếu {@code name} đang là thành viên của nhóm
     */
    public boolean contains(String name) {
        return name != null && members.contains(name);
    }

    /**
     * Thêm thành viên (dùng khi khởi tạo hoặc mở rộng nhóm sau này).
     *
     * @param name tên thành viên
     * @return {@code true} nếu tập thành viên thay đổi
     */
    public boolean addMember(String name) {
        return name != null && members.add(name);
    }

    /**
     * Xóa một thành viên khỏi nhóm.
     *
     * @param name tên thành viên
     * @return {@code true} nếu thành viên đã được xóa
     */
    public boolean removeMember(String name) {
        return name != null && members.remove(name);
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    /**
     * @return bản sao danh sách thành viên tại thời điểm gọi (an toàn khi đang phát tin)
     */
    public List<String> snapshotMembers() {
        return new ArrayList<>(members);
    }
}
