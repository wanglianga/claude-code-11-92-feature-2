package com.port.berth.repo;

import com.port.berth.hazmat.Hazmat.Cargo;
import com.port.berth.hazmat.Hazmat.Clearance;
import com.port.berth.hazmat.Hazmat.IsolationRule;
import com.port.berth.hazmat.Hazmat.Occupation;
import com.port.berth.hazmat.Hazmat.RoadState;
import com.port.berth.hazmat.Hazmat.Snapshot;
import com.port.berth.hazmat.Hazmat.Yard;
import com.port.berth.hazmat.Hazmat.YardDistance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 危货临时堆区事实的 JDBC 存取与固定样例播种。 */
@Repository
public class HazmatDao {

    /** 固定样例日，与 SeedService.D 同一天 */
    public static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 26, 0, 0);

    private final JdbcTemplate jdbc;

    public HazmatDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int yardCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM hz_yard", Integer.class);
        return n == null ? 0 : n;
    }

    public synchronized void seedIfEmpty() {
        if (yardCount() > 0) return;
        doSeed();
    }

    /** 清空危货堆区相关表并恢复固定样例（管理端重置/回放调用）。 */
    public synchronized void resetAndSeed() {
        for (String t : List.of("hz_occupation", "hz_clearance", "hz_road_state",
                "hz_cargo", "hz_isolation_rule", "hz_yard_distance", "hz_yard")) {
            jdbc.update("DELETE FROM " + t);
        }
        doSeed();
    }

    private void doSeed() {
        // 堆位：容量 20/15/15；Y1 为甲类固定专用且允许“清场后分时共享”乙
        yard("Y1", "一号危货临时堆位", "20", "JIA", true, "FIRE-Y1", 1);
        yard("Y2", "二号危货临时堆位", "15", null, false, null, 2);
        yard("Z", "Z 远端隔离堆位", "15", "YI", false, null, 3);

        // 距离：Y1-Y2=30m（不满足甲乙 50m）；Z 距两者均 >50m
        distance("Y1", "Y2", 30);
        distance("Y1", "Z", 55);
        distance("Y2", "Z", 58);

        // 甲乙类必须隔离 50m
        jdbc.update("INSERT INTO hz_isolation_rule(class_a,class_b,min_distance_m) VALUES ('JIA','YI',50)");

        LocalDateTime arrive = DAY.withHour(9).withMinute(0);
        cargo("JIA", "甲类危货（12t）", "JIA", "12", arrive, true,
                "与乙货同一小时到港；获批转运车先放 Y1，10:00 送场外合规区");
        cargo("YI", "乙类危货（10t）", "YI", "10", arrive, true,
                "与甲货同一小时到港；需与甲隔离 50m");

        // 甲 09:00 进 Y1，10:00 实际离场（有离场证明）；Z 既有作业 08:00-11:00 占满
        occupation("Y1", "JIA", "JIA", "甲货·获批转运车", "CARGO", "12",
                arrive, DAY.withHour(10), DAY.withHour(10));
        occupation("Z", null, null, "Z 既有作业（检修/倒垛占用）", "OPERATION", "15",
                DAY.withHour(8), DAY.withHour(11), null);

        // Y1 清场复核：计划 10:20，凭证准时 10:20 生效
        saveClearance(new Clearance("Y1", "CL-Y1-1020",
                DAY.withHour(10).withMinute(20),
                DAY.withHour(10).withMinute(20), true));

        // 通往 Y1 的消防通道/转运道路巡查畅通
        saveRoad(new RoadState("FIRE-Y1", "Y1 消防通道/转运道路", true,
                DAY.withHour(10).withMinute(15), null, "巡查畅通，无堆垛占用"));
    }

    private void yard(String code, String name, String cap, String dedicated,
                      boolean share, String road, int sort) {
        jdbc.update("""
                INSERT INTO hz_yard(code,name,capacity_t,dedicated_class,shared_clearance,
                                    access_road_code,sort_no)
                VALUES (?,?,?,?,?,?,?)
                """, code, name, new BigDecimal(cap), dedicated, share, road, sort);
    }

    private void distance(String a, String b, int m) {
        jdbc.update("INSERT INTO hz_yard_distance(yard_a,yard_b,distance_m) VALUES (?,?,?)", a, b, m);
    }

    private void cargo(String code, String name, String cls, String tons,
                       LocalDateTime arrived, boolean approved, String note) {
        jdbc.update("""
                INSERT INTO hz_cargo(code,name,haz_class,tons,arrived_at,transfer_approved,note)
                VALUES (?,?,?,?,?,?,?)
                """, code, name, cls, new BigDecimal(tons), ts(arrived), approved, note);
    }

    private void occupation(String yard, String cargo, String cls, String owner, String kind,
                            String tons, LocalDateTime start, LocalDateTime end,
                            LocalDateTime departed) {
        jdbc.update("""
                INSERT INTO hz_occupation(yard_code,cargo_code,haz_class,owner_label,kind,tons,
                                          start_at,end_at,departed_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, yard, cargo, cls, owner, kind, new BigDecimal(tons),
                ts(start), ts(end), ts(departed));
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    public Snapshot loadSnapshot(LocalDateTime at) {
        List<Yard> yards = jdbc.query(
                "SELECT * FROM hz_yard ORDER BY sort_no, code",
                (rs, i) -> new Yard(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getBigDecimal("capacity_t"),
                        rs.getString("dedicated_class"),
                        rs.getBoolean("shared_clearance"),
                        rs.getString("access_road_code"),
                        rs.getInt("sort_no")));

        List<YardDistance> dists = jdbc.query(
                "SELECT yard_a,yard_b,distance_m FROM hz_yard_distance",
                (rs, i) -> new YardDistance(rs.getString("yard_a"),
                        rs.getString("yard_b"), rs.getInt("distance_m")));

        List<Cargo> cargos = jdbc.query(
                "SELECT code,name,haz_class,tons,arrived_at,transfer_approved FROM hz_cargo",
                (rs, i) -> new Cargo(
                        rs.getString("code"), rs.getString("name"),
                        rs.getString("haz_class"), rs.getBigDecimal("tons"),
                        rs.getObject("arrived_at", LocalDateTime.class),
                        rs.getBoolean("transfer_approved")));

        List<IsolationRule> rules = jdbc.query(
                "SELECT class_a,class_b,min_distance_m FROM hz_isolation_rule",
                (rs, i) -> new IsolationRule(rs.getString("class_a"),
                        rs.getString("class_b"), rs.getInt("min_distance_m")));

        List<Occupation> occs = jdbc.query(
                "SELECT id,yard_code,cargo_code,haz_class,owner_label,kind,tons,"
                        + "start_at,end_at,departed_at FROM hz_occupation ORDER BY id",
                (rs, i) -> new Occupation(
                        rs.getLong("id"), rs.getString("yard_code"),
                        rs.getString("cargo_code"), rs.getString("haz_class"),
                        rs.getString("owner_label"), rs.getString("kind"),
                        rs.getBigDecimal("tons"),
                        rs.getObject("start_at", LocalDateTime.class),
                        rs.getObject("end_at", LocalDateTime.class),
                        rs.getObject("departed_at", LocalDateTime.class)));

        Map<String, Clearance> certs = new LinkedHashMap<>();
        jdbc.query("SELECT yard_code,cert_no,planned_ready_at,certified_at,review_passed "
                        + "FROM hz_clearance",
                rs -> {
                    certs.put(rs.getString("yard_code"), new Clearance(
                            rs.getString("yard_code"), rs.getString("cert_no"),
                            rs.getObject("planned_ready_at", LocalDateTime.class),
                            rs.getObject("certified_at", LocalDateTime.class),
                            rs.getBoolean("review_passed")));
                });

        Map<String, RoadState> roads = new LinkedHashMap<>();
        jdbc.query("SELECT code,name,is_clear,checked_at,restore_at,reason FROM hz_road_state",
                rs -> {
                    roads.put(rs.getString("code"), new RoadState(
                            rs.getString("code"), rs.getString("name"),
                            rs.getBoolean("is_clear"),
                            rs.getObject("checked_at", LocalDateTime.class),
                            rs.getObject("restore_at", LocalDateTime.class),
                            rs.getString("reason")));
                });

        LocalDateTime decisionAt = at != null ? at : DAY.withHour(9);
        return new Snapshot(DAY, decisionAt, yards, dists, cargos, rules, occs, certs, roads);
    }

    // ------------------------------------------------------------------
    // 变更
    // ------------------------------------------------------------------

    public Clearance saveClearance(Clearance c) {
        jdbc.update("""
                INSERT INTO hz_clearance(yard_code,cert_no,planned_ready_at,certified_at,
                                         review_passed,updated_at)
                VALUES (?,?,?,?,?,now())
                ON CONFLICT (yard_code) DO UPDATE SET
                    cert_no=EXCLUDED.cert_no,
                    planned_ready_at=EXCLUDED.planned_ready_at,
                    certified_at=EXCLUDED.certified_at,
                    review_passed=EXCLUDED.review_passed,
                    updated_at=now()
                """, c.yardCode(), c.certNo(), ts(c.plannedReadyAt()),
                ts(c.certifiedAt()), c.reviewPassed());
        return c;
    }

    public RoadState saveRoad(RoadState r) {
        int updated = jdbc.update("""
                UPDATE hz_road_state SET name=?, is_clear=?, checked_at=?, restore_at=?, reason=?
                WHERE code=?
                """, r.name(), r.clear(), ts(r.checkedAt()), ts(r.restoreAt()),
                r.reason(), r.code());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO hz_road_state(code,name,is_clear,checked_at,restore_at,reason)
                    VALUES (?,?,?,?,?,?)
                    """, r.code(), r.name(), r.clear(), ts(r.checkedAt()),
                    ts(r.restoreAt()), r.reason());
        }
        return r;
    }

    /** 记录某堆位当前在堆危货的实际离场时间（“甲货已离场”证明）。 */
    public int markDeparture(String yardCode, LocalDateTime at) {
        return jdbc.update("""
                UPDATE hz_occupation SET departed_at=?
                WHERE yard_code=? AND kind='CARGO' AND departed_at IS NULL
                """, ts(at == null ? LocalDateTime.now() : at), yardCode);
    }

    public Occupation insertOccupation(String yardCode, String cargoCode, String hazClass,
                                       String owner, String kind, BigDecimal tons,
                                       LocalDateTime startAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO hz_occupation(yard_code,cargo_code,haz_class,owner_label,kind,tons,
                                              start_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setString(1, yardCode);
            ps.setString(2, cargoCode);
            ps.setString(3, hazClass);
            ps.setString(4, owner);
            ps.setString(5, kind == null ? "CARGO" : kind);
            ps.setBigDecimal(6, tons);
            ps.setTimestamp(7, ts(startAt));
            return ps;
        }, kh);
        Long id = null;
        if (!kh.getKeyList().isEmpty() && kh.getKeyList().get(0).get("id") instanceof Number n) {
            id = n.longValue();
        } else if (kh.getKey() != null) {
            id = kh.getKey().longValue();
        }
        return new Occupation(id, yardCode, cargoCode, hazClass, owner,
                kind == null ? "CARGO" : kind, tons, startAt, null, null);
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }
}
