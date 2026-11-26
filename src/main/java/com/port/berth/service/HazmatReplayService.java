package com.port.berth.service;

import com.port.berth.hazmat.Hazmat.Reason;
import com.port.berth.hazmat.Hazmat.Strategy;
import com.port.berth.repo.HazmatDao;
import com.port.berth.web.ApiException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特性 92-feature-2 一键回放：
 * 两种安排的可接货时间/占用量/拒绝理由，以及“清场凭证迟到不得显示可用”的硬不变量，
 * 另含消防通道闸门与真实接货 409/200 路径。每块开始先重置固定样例。
 */
@Service
public class HazmatReplayService {

    private final HazmatService hazmat;

    public HazmatReplayService(HazmatService hazmat) {
        this.hazmat = hazmat;
    }

    private LocalDateTime t(int h, int m) {
        return HazmatDao.DAY.withHour(h).withMinute(m);
    }

    /** 每个断言块前只重置危货子域：隔离且不清空 auth_token，调用方登录态保持有效。 */
    private void resetHz() {
        hazmat.resetHazmat();
    }

    public Map<String, Object> runHazmat() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "Y1/Y2/Z=20/15/15t；Y1-Y2=30m；甲乙隔离50m；"
                + "甲12t 10:00 离场；Y1 清场计划10:20；Z 占用至11:00");

        Map<String, Object> comparison = comparisonBlock();
        Map<String, Object> cert = certificateGuardBlock();
        Map<String, Object> road = roadGateBlock();
        Map<String, Object> receiving = receivingBlock();

        report.put("comparison", comparison);
        report.put("certificateGuard", cert);
        report.put("roadGate", road);
        report.put("receiving", receiving);

        boolean all = Boolean.TRUE.equals(comparison.get("allPassed"))
                && Boolean.TRUE.equals(cert.get("allPassed"))
                && Boolean.TRUE.equals(road.get("allPassed"))
                && Boolean.TRUE.equals(receiving.get("allPassed"));
        report.put("allPassed", all);
        return report;
    }

    // ------------------------------------------------------------------
    // 块 1：两种安排对照
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> comparisonBlock() {
        resetHz();
        List<Map<String, Object>> checks = new ArrayList<>();
        Map<String, Object> cmp = hazmat.comparison(HazmatService.ARRIVAL);

        List<Map<String, Object>> arrangements =
                (List<Map<String, Object>>) cmp.get("arrangements");
        Map<String, Object> fixed = arrangement(arrangements, "FIXED_DEDICATED");
        Map<String, Object> shared = arrangement(arrangements, "TIME_SHARED_CLEARANCE");

        check(checks, "固定专用区：乙分配到 Z",
                "Z", fixed.get("assignedYardCode"), "Z".equals(fixed.get("assignedYardCode")));
        check(checks, "固定专用区：乙最早 11:00 可接（等 Z 腾空）",
                HazmatService.T1100, fixed.get("earliestAcceptAt"),
                HazmatService.T1100.equals(fixed.get("earliestAcceptAt")));
        check(checks, "固定专用区：等待 120 分钟（09:00→11:00）",
                120, fixed.get("waitMinutesFromArrival"),
                Integer.valueOf(120).equals(fixed.get("waitMinutesFromArrival")));

        check(checks, "固定安排拒绝 Y1：ZONE_DEDICATED（甲类专用不跨类）",
                true, codes(fixed, "Y1").contains("ZONE_DEDICATED"),
                codes(fixed, "Y1").contains("ZONE_DEDICATED"));
        check(checks, "固定安排拒绝 Y2：隔离距离不足 30m<50m",
                true, codes(fixed, "Y2").contains("ISOLATION_DISTANCE_INSUFFICIENT"),
                codes(fixed, "Y2").contains("ISOLATION_DISTANCE_INSUFFICIENT"));
        check(checks, "固定安排拒绝 Z（当时）：YARD_OCCUPIED_UNTIL 11:00",
                true, codes(fixed, "Z").contains("YARD_OCCUPIED_UNTIL"),
                codes(fixed, "Z").contains("YARD_OCCUPIED_UNTIL"));

        check(checks, "清场共享：乙分配到 Y1",
                "Y1", shared.get("assignedYardCode"),
                "Y1".equals(shared.get("assignedYardCode")));
        check(checks, "清场共享：三证齐全后 10:20 可接乙",
                HazmatService.T1020, shared.get("earliestAcceptAt"),
                HazmatService.T1020.equals(shared.get("earliestAcceptAt")));
        check(checks, "清场共享：等待 80 分钟（09:00→10:20）",
                80, shared.get("waitMinutesFromArrival"),
                Integer.valueOf(80).equals(shared.get("waitMinutesFromArrival")));
        check(checks, "共享 Y1 比死等 Z 提早 40 分钟",
                40, cmp.get("savingMinutes"),
                cmp.get("savingMinutes") instanceof Number n && n.intValue() == 40);

        // 占用量：09:00 Y1=12/20、Z=15/15
        List<Map<String, Object>> timeline =
                (List<Map<String, Object>>) cmp.get("occupancyTimeline");
        Map<String, Object> at0900 = timeline.get(0);
        Map<String, Object> y1 = yardOcc(at0900, "Y1");
        Map<String, Object> z = yardOcc(at0900, "Z");
        check(checks, "09:00 占用量 Y1=12t/20t", 12,
                ((Number) y1.get("occupiedT")).intValue(),
                ((Number) y1.get("occupiedT")).intValue() == 12
                        && ((Number) y1.get("capacityT")).intValue() == 20);
        check(checks, "09:00 占用量 Z=15t/15t（既有作业占满）", 15,
                ((Number) z.get("occupiedT")).intValue(),
                ((Number) z.get("occupiedT")).intValue() == 15);

        return block("两种安排：可接货时间 / 占用量 / 被拒理由", checks);
    }

    // ------------------------------------------------------------------
    // 块 2：清场凭证迟到 → 不得显示可用
    // ------------------------------------------------------------------

    private Map<String, Object> certificateGuardBlock() {
        resetHz();
        List<Map<String, Object>> checks = new ArrayList<>();

        Map<String, Object> b1019 = hazmat.board(t(10, 19));
        check(checks, "凭证生效前 10:19：Y1 不可用", false,
                yard(b1019, "Y1").get("available"),
                Boolean.FALSE.equals(yard(b1019, "Y1").get("available")));

        Map<String, Object> b1020 = hazmat.board(HazmatService.T1020);
        check(checks, "准时凭证 10:20 生效：Y1 显示 AVAILABLE/可用",
                "AVAILABLE", yard(b1020, "Y1").get("status"),
                Boolean.TRUE.equals(yard(b1020, "Y1").get("available"))
                        && "AVAILABLE".equals(yard(b1020, "Y1").get("status")));

        // 凭证迟到：计划 10:20，实际 10:40 才生效
        hazmat.registerClearance("Y1", "CL-Y1-LATE",
                HazmatService.T1020, t(10, 40), true);

        Map<String, Object> late1020 = hazmat.board(HazmatService.T1020);
        Map<String, Object> y1At1020 = yard(late1020, "Y1");
        check(checks, "凭证迟到：10:20 已过计划点但 Y1 仍不得显示可用",
                false, y1At1020.get("available"),
                Boolean.FALSE.equals(y1At1020.get("available")));
        check(checks, "凭证迟到：状态为 CERT_LATE 且带 CLEARANCE_CERT_LATE 理由",
                "CERT_LATE", y1At1020.get("status"),
                "CERT_LATE".equals(y1At1020.get("status"))
                        && codesOf(y1At1020).contains("CLEARANCE_CERT_LATE"));
        check(checks, "凭证迟到：certificateLate 标记为 true",
                true, y1At1020.get("certificateLate"),
                Boolean.TRUE.equals(y1At1020.get("certificateLate")));

        Map<String, Object> late1039 = hazmat.board(t(10, 39));
        check(checks, "凭证迟到：10:39（生效前一分钟）Y1 仍不可用", false,
                yard(late1039, "Y1").get("available"),
                Boolean.FALSE.equals(yard(late1039, "Y1").get("available")));

        Map<String, Object> late1040 = hazmat.board(t(10, 40));
        check(checks, "凭证 10:40 实际生效后：Y1 才可用", true,
                yard(late1040, "Y1").get("available"),
                Boolean.TRUE.equals(yard(late1040, "Y1").get("available")));

        Map<String, Object> cmp = hazmat.comparison(HazmatService.ARRIVAL);
        Map<String, Object> shared = arrangement(
                (List<Map<String, Object>>) cmp.get("arrangements"),
                "TIME_SHARED_CLEARANCE");
        check(checks, "凭证迟到后：共享安排最早可接时间顺延为 10:40",
                t(10, 40), shared.get("earliestAcceptAt"),
                t(10, 40).equals(shared.get("earliestAcceptAt")));

        // 凭证缺失：即使到 11:00 也不可用
        hazmat.registerClearance("Y1", null, HazmatService.T1020, null, false);
        Map<String, Object> missing = hazmat.board(HazmatService.T1100);
        Map<String, Object> y1Missing = yard(missing, "Y1");
        check(checks, "凭证缺失/未通过：即使 11:00 Y1 也不得显示可用",
                "CERT_MISSING", y1Missing.get("status"),
                Boolean.FALSE.equals(y1Missing.get("available"))
                        && ("CERT_MISSING".equals(y1Missing.get("status"))
                            || "CLEARANCE_FAILED".equals(y1Missing.get("status"))));

        return block("清场凭证闸门：迟到/缺失时堆位不得显示为可用", checks);
    }

    // ------------------------------------------------------------------
    // 块 3：消防通道闸门
    // ------------------------------------------------------------------

    private Map<String, Object> roadGateBlock() {
        resetHz();
        List<Map<String, Object>> checks = new ArrayList<>();

        hazmat.setRoad("FIRE-Y1", false, "回放模拟：消防通道被临时堆垛占用",
                HazmatService.T1020, null);
        Map<String, Object> b = hazmat.board(HazmatService.T1020);
        Map<String, Object> y1 = yard(b, "Y1");
        check(checks, "消防通道阻断：10:20 Y1 不可用且状态 ROAD_BLOCKED",
                "ROAD_BLOCKED", y1.get("status"),
                Boolean.FALSE.equals(y1.get("available"))
                        && "ROAD_BLOCKED".equals(y1.get("status"))
                        && codesOf(y1).contains("FIRE_LANE_BLOCKED"));

        ApiException rejected = expectReject(() ->
                hazmat.receive("YI", Strategy.TIME_SHARED_CLEARANCE, "Y1",
                        HazmatService.T1020));
        boolean roadInDetails = rejected != null && rejected.getStatus() == 409
                && detailCodes(rejected).contains("FIRE_LANE_BLOCKED");
        check(checks, "通道阻断时接货：HTTP 409 且理由含 FIRE_LANE_BLOCKED",
                true, roadInDetails, roadInDetails);

        hazmat.setRoad("FIRE-Y1", true, "通道已清理，巡查畅通",
                HazmatService.T1020, null);
        Map<String, Object> after = hazmat.board(HazmatService.T1020);
        check(checks, "恢复畅通后：Y1 立即恢复可接货", true,
                yard(after, "Y1").get("available"),
                Boolean.TRUE.equals(yard(after, "Y1").get("available")));

        return block("消防通道/转运道路：不畅通必须拒绝接货", checks);
    }

    // ------------------------------------------------------------------
    // 块 4：真实接货 409 / 成功
    // ------------------------------------------------------------------

    private Map<String, Object> receivingBlock() {
        resetHz();
        List<Map<String, Object>> checks = new ArrayList<>();

        ApiException fixedY1 = expectReject(() ->
                hazmat.receive("YI", Strategy.FIXED_DEDICATED, "Y1",
                        HazmatService.T1020));
        boolean fixedY1Ok = fixedY1 != null && fixedY1.getStatus() == 409
                && "HAZMAT_REJECTED".equals(fixedY1.getCode())
                && detailCodes(fixedY1).contains("ZONE_DEDICATED");
        check(checks, "固定安排 10:20 强接 Y1：409 + ZONE_DEDICATED",
                true, fixedY1Ok, fixedY1Ok);

        ApiException zAt1020 = expectReject(() ->
                hazmat.receive("YI", Strategy.FIXED_DEDICATED, "Z",
                        HazmatService.T1020));
        boolean zOk = zAt1020 != null && zAt1020.getStatus() == 409
                && detailCodes(zAt1020).contains("YARD_OCCUPIED_UNTIL");
        check(checks, "10:20 接 Z：409 + YARD_OCCUPIED_UNTIL（要等到11:00）",
                true, zOk, zOk);

        ApiException early = expectReject(() ->
                hazmat.receive("YI", Strategy.TIME_SHARED_CLEARANCE, "Y1", t(10, 19)));
        boolean earlyOk = early != null && early.getStatus() == 409
                && detailCodes(early).contains("CLEARANCE_CERT_PENDING");
        check(checks, "共享 Y1 在 10:19（凭证未生效）接货：409 + CLEARANCE_CERT_PENDING",
                true, earlyOk, earlyOk);

        Map<String, Object> ok = hazmat.receive("YI",
                Strategy.TIME_SHARED_CLEARANCE, "Y1", HazmatService.T1020);
        check(checks, "三证齐全 10:20 接乙进 Y1：成功 accepted=true",
                true, ok.get("accepted"), Boolean.TRUE.equals(ok.get("accepted"))
                        && "Y1".equals(ok.get("yardCode")));
        Object occ = ok.get("occupation");
        boolean persisted = occ instanceof com.port.berth.hazmat.Hazmat.Occupation o
                && o.id() != null && o.id() > 0;
        check(checks, "接货成功后写入 hz_occupation 并返回持久化 ID",
                true, persisted, persisted);

        return block("接货放行：结构化拒绝理由与成功落库", checks);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<String, Object> block(String title, List<Map<String, Object>> checks) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", title);
        b.put("checks", checks);
        b.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        return b;
    }

    private void check(List<Map<String, Object>> checks, String name,
                       Object expected, Object actual, boolean pass) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("pass", pass);
        c.put("expected", expected);
        c.put("actual", actual);
        checks.add(c);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> arrangement(List<Map<String, Object>> list, String strategy) {
        return list.stream().filter(a -> strategy.equals(a.get("strategy"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少安排 " + strategy));
    }

    @SuppressWarnings("unchecked")
    private List<String> codes(Map<String, Object> arrangement, String yard) {
        List<Map<String, Object>> rs =
                (List<Map<String, Object>>) arrangement.get("rejectionSummary");
        return rs.stream().filter(r -> yard.equals(r.get("yardCode"))).findFirst()
                .map(r -> (List<String>) r.get("reasonCodes"))
                .orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yardOcc(Map<String, Object> probe, String code) {
        return ((List<Map<String, Object>>) probe.get("yards")).stream()
                .filter(y -> code.equals(y.get("yardCode"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yard(Map<String, Object> board, String code) {
        return ((List<Map<String, Object>>) board.get("yards")).stream()
                .filter(y -> code.equals(y.get("yardCode"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<String> codesOf(Map<String, Object> yardRow) {
        Object o = yardRow.get("reasonCodes");
        return o instanceof List ? (List<String>) o : List.of();
    }

    private ApiException expectReject(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException e) {
            return e;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> detailCodes(ApiException e) {
        Object d = e.getDetails();
        if (d instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Reason r) out.add(r.code());
                else if (o instanceof Map<?, ?> m && m.get("code") != null)
                    out.add(String.valueOf(m.get("code")));
            }
            return out;
        }
        return List.of();
    }
}
