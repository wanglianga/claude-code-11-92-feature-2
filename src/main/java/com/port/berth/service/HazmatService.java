package com.port.berth.service;

import com.port.berth.hazmat.Hazmat.Cargo;
import com.port.berth.hazmat.Hazmat.CargoDecision;
import com.port.berth.hazmat.Hazmat.Clearance;
import com.port.berth.hazmat.Hazmat.Occupation;
import com.port.berth.hazmat.Hazmat.Reason;
import com.port.berth.hazmat.Hazmat.RoadState;
import com.port.berth.hazmat.Hazmat.Snapshot;
import com.port.berth.hazmat.Hazmat.Strategy;
import com.port.berth.hazmat.Hazmat.Yard;
import com.port.berth.hazmat.Hazmat.YardOption;
import com.port.berth.hazmat.HazmatEngine;
import com.port.berth.repo.HazmatDao;
import com.port.berth.web.ApiException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 危货临时堆区：两种接货安排对照、堆位可用性看板、清场凭证/道路/离场证明登记与接货放行。
 */
@Service
public class HazmatService {

    public static final LocalDateTime ARRIVAL = HazmatDao.DAY.withHour(9).withMinute(0);
    public static final LocalDateTime T1000 = HazmatDao.DAY.withHour(10).withMinute(0);
    public static final LocalDateTime T1020 = HazmatDao.DAY.withHour(10).withMinute(20);
    /** 演示用“凭证迟到”实际生效时刻（晚于计划 10:20）。 */
    public static final LocalDateTime T1040 = HazmatDao.DAY.withHour(10).withMinute(40);
    public static final LocalDateTime T1100 = HazmatDao.DAY.withHour(11).withMinute(0);
    /** 看板默认观察时刻：计划中的 Y1 清场完成点，最能暴露“凭证迟到伪装可用” */
    public static final LocalDateTime DEFAULT_VIEW_AT = T1020;

    private final HazmatDao dao;

    public HazmatService(HazmatDao dao) {
        this.dao = dao;
    }

    // ------------------------------------------------------------------
    // 场景事实
    // ------------------------------------------------------------------

    public Map<String, Object> scenario() {
        Snapshot snap = dao.loadSnapshot(ARRIVAL);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshot", snap);
        m.put("keyTimes", keyTimes());
        m.put("rules", Map.of(
                "isolationM", 50,
                "capacityT", Map.of("Y1", 20, "Y2", 15, "Z", 15),
                "distanceM", Map.of("Y1-Y2", 30, "Y1-Z", ">50（样例取55）", "Y2-Z", ">50（样例取58）")));
        return m;
    }

    public Map<String, Object> keyTimes() {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("arrival", ARRIVAL);
        k.put("jiaDepartY1", T1000);
        k.put("plannedClearanceReady", T1020);
        k.put("zFreed", T1100);
        return k;
    }

    // ------------------------------------------------------------------
    // 两种安排对照
    // ------------------------------------------------------------------

    public Map<String, Object> comparison(LocalDateTime at) {
        LocalDateTime evalAt = at != null ? at : ARRIVAL;
        Snapshot snap = dao.loadSnapshot(evalAt);

        CargoDecision fixed = HazmatEngine.decide(snap, "YI", Strategy.FIXED_DEDICATED, evalAt);
        CargoDecision shared = HazmatEngine.decide(snap, "YI",
                Strategy.TIME_SHARED_CLEARANCE, evalAt);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario", "甲12t/乙10t 同一小时到港；甲乙隔离50m；Y1/Y2/Z=20/15/15t，"
                + "Y1-Y2=30m，Z 距两者>50m");
        m.put("evaluatedAt", evalAt);
        m.put("keyTimes", keyTimes());
        m.put("arrangements", List.of(
                arrangementView(snap, fixed,
                        "固定专用区",
                        "货类与堆位一一固定，放行判断直接；但乙的专用隔离位只有 Z，"
                                + "Z 被既有作业占到 11:00，乙只能等到 11:00。"),
                arrangementView(snap, shared,
                        "按清场时间共享 Y1",
                        "甲 10:00 离场后，以“实际离场证明 + 清场凭证生效 + 消防通道畅通”"
                                + "三证为闸门，Y1 可在 10:20 接乙，比死等 Z 提早 40 分钟。")));
        m.put("fixedEarliestAcceptAt", fixed.earliestAcceptAt());
        m.put("sharedEarliestAcceptAt", shared.earliestAcceptAt());
        if (fixed.earliestAcceptAt() != null && shared.earliestAcceptAt() != null) {
            m.put("savingMinutes", java.time.Duration.between(
                    shared.earliestAcceptAt(), fixed.earliestAcceptAt()).toMinutes());
        }
        m.put("occupancyTimeline", occupancyTimeline(snap));
        m.put("certificateGuard", Map.of(
                "rule", "Y1 是否可用只以清场凭证的实际生效时间 certifiedAt 为准；"
                        + "凭证迟到（晚于计划 10:20）或缺失/复核未过时，在凭证生效或补录前，"
                        + "任何时刻都不得把 Y1 显示为可用。",
                "plannedReadyAt", T1020));
        m.put("conclusionZh", conclusion(fixed, shared));
        return m;
    }

    private String conclusion(CargoDecision fixed, CargoDecision shared) {
        StringBuilder sb = new StringBuilder();
        sb.append("① 固定专用区：乙最早 ").append(HazmatEngine.hm(fixed.earliestAcceptAt()))
                .append(" 进 ").append(fixed.assignedYardCode())
                .append("（Z 既有作业占用至 11:00；Y1 为甲类专用、Y2 距 Y1 仅 30m<50m 均被拒）。");
        sb.append("② 清场共享 Y1：乙最早 ").append(HazmatEngine.hm(shared.earliestAcceptAt()))
                .append(" 进 Y1（须甲已离场、清场凭证生效、消防通道畅通）。");
        if (fixed.earliestAcceptAt() != null && shared.earliestAcceptAt() != null) {
            long save = java.time.Duration.between(
                    shared.earliestAcceptAt(), fixed.earliestAcceptAt()).toMinutes();
            sb.append("共享 Y1 可提早 ").append(save).append(" 分钟接货。");
        }
        return sb.toString();
    }

    private Map<String, Object> arrangementView(Snapshot snap, CargoDecision d,
                                                String labelZh, String narrative) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("strategy", d.strategy().name());
        a.put("labelZh", labelZh);
        a.put("narrative", narrative);
        a.put("assignedYardCode", d.assignedYardCode());
        a.put("earliestAcceptAt", d.earliestAcceptAt());
        a.put("waitMinutesFromArrival", d.waitMinutesFromArrival());
        a.put("acceptableNow", d.acceptableNow());
        a.put("decision", d);

        List<Map<String, Object>> rej = new ArrayList<>();
        for (YardOption o : d.options()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("yardCode", o.yardCode());
            r.put("eligible", o.eligible());
            r.put("earliestAcceptAt", o.earliestAcceptAt());
            r.put("reasonCodes", o.reasons().stream().map(Reason::code).toList());
            r.put("reasons", o.reasons());
            rej.add(r);
        }
        a.put("rejectionSummary", rej);

        if (d.selected() != null) {
            Yard y = snap.yard(d.assignedYardCode());
            Map<String, Object> load = new LinkedHashMap<>();
            load.put("yardCode", y.code());
            load.put("incomingT", snap.cargo(d.cargoCode()).tons());
            load.put("capacityT", y.capacityT());
            load.put("utilizationAfterPct", HazmatEngine.pct(
                    snap.cargo(d.cargoCode()).tons(), y.capacityT()));
            a.put("acceptedLoad", load);
        }
        return a;
    }

    /** 关键时刻各堆位占用量（吨/容量/利用率）。 */
    private List<Map<String, Object>> occupancyTimeline(Snapshot snap) {
        LocalDateTime[] probes = {ARRIVAL, T1000, T1020, T1100};
        String[] labels = {"到货 09:00", "甲离场 10:00", "计划清场完成 10:20", "Z 腾空 11:00"};
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (int i = 0; i < probes.length; i++) {
            LocalDateTime t = probes[i];
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("at", t);
            p.put("label", labels[i]);
            List<Map<String, Object>> yards = new ArrayList<>();
            for (Yard y : snap.yards()) {
                BigDecimal occ = HazmatEngine.occupiedTons(snap, y.code(), t);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("yardCode", y.code());
                row.put("occupiedT", occ);
                row.put("capacityT", y.capacityT());
                row.put("remainingT", y.capacityT().subtract(occ));
                row.put("utilizationPct", HazmatEngine.pct(occ, y.capacityT()));
                yards.add(row);
            }
            p.put("yards", yards);
            timeline.add(p);
        }
        return timeline;
    }

    // ------------------------------------------------------------------
    // 堆位可用性看板（默认停在 10:20 这个“凭证可能迟到”的裁判时刻）
    // ------------------------------------------------------------------

    public Map<String, Object> board(LocalDateTime at) {
        LocalDateTime t = at != null ? at : DEFAULT_VIEW_AT;
        Snapshot snap = dao.loadSnapshot(t);
        Cargo yi = snap.cargo("YI");

        List<Map<String, Object>> yards = new ArrayList<>();
        for (Yard y : snap.yards()) {
            YardOption opt = HazmatEngine.evaluateYard(
                    snap, yi, y, Strategy.TIME_SHARED_CLEARANCE, t);
            BigDecimal occ = HazmatEngine.occupiedTons(snap, y.code(), t);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("yardCode", y.code());
            row.put("yardName", y.name());
            row.put("capacityT", y.capacityT());
            row.put("occupiedT", occ);
            row.put("remainingT", y.capacityT().subtract(occ));
            row.put("utilizationPct", HazmatEngine.pct(occ, y.capacityT()));
            row.put("dedicatedClass", y.dedicatedClass());
            row.put("sharedClearance", y.sharedClearance());
            row.put("available", opt.acceptableNow());
            row.put("earliestAcceptAt", opt.earliestAcceptAt());
            row.put("status", statusCode(opt));
            row.put("statusZh", statusZh(opt));
            row.put("reasonCodes", opt.reasons().stream().map(Reason::code).toList());
            row.put("reasons", opt.reasons());
            row.put("gates", opt.gates());
            row.put("clearance", snap.clearances().get(y.code()));
            row.put("road", y.accessRoadCode() == null ? null
                    : snap.roads().get(y.accessRoadCode()));

            Clearance cl = snap.clearances().get(y.code());
            if (cl != null) {
                row.put("certificateEffective", cl.effectiveAt(t));
                row.put("certificateLate", cl.issued() && cl.plannedReadyAt() != null
                        && cl.certifiedAt() != null
                        && cl.certifiedAt().isAfter(cl.plannedReadyAt()));
            } else {
                row.put("certificateEffective", false);
                row.put("certificateLate", false);
            }
            yards.add(row);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", t);
        m.put("defaultViewAt", DEFAULT_VIEW_AT);
        m.put("yards", yards);
        m.put("guard", "清场凭证迟到/缺失时，对应堆位 available 必须为 false，状态不得为 AVAILABLE。");
        return m;
    }

    private String statusCode(YardOption o) {
        if (o.acceptableNow()) return "AVAILABLE";
        List<String> codes = o.reasons().stream().map(Reason::code).toList();
        if (codes.contains("CAPACITY_EXCEEDED")) return "CAPACITY_EXCEEDED";
        if (codes.contains("ZONE_DEDICATED") || codes.contains("YARD_NOT_DESIGNATED"))
            return "NOT_DESIGNATED";
        if (codes.contains("FIRE_LANE_BLOCKED")) return "ROAD_BLOCKED";
        if (codes.contains("CLEARANCE_REVIEW_FAILED")) return "CLEARANCE_FAILED";
        if (codes.contains("CLEARANCE_CERT_REQUIRED")) return "CERT_MISSING";
        if (codes.contains("CLEARANCE_CERT_LATE")) return "CERT_LATE";
        if (codes.contains("CLEARANCE_CERT_PENDING")) return "PENDING_CLEARANCE";
        if (codes.contains("DEPARTURE_PROOF_REQUIRED")) return "DEPARTURE_PROOF_MISSING";
        if (codes.contains("ISOLATION_DISTANCE_INSUFFICIENT")) return "ISOLATION_BLOCKED";
        if (codes.stream().anyMatch(c -> c.startsWith("YARD_OCCUPIED"))) return "OCCUPIED";
        if (codes.contains("TRANSFER_NOT_APPROVED")) return "TRANSFER_NOT_APPROVED";
        return "UNAVAILABLE";
    }

    private String statusZh(YardOption o) {
        return switch (statusCode(o)) {
            case "AVAILABLE" -> "可接货";
            case "CAPACITY_EXCEEDED" -> "容量不足";
            case "NOT_DESIGNATED" -> "非该货类指定/共享堆位";
            case "ROAD_BLOCKED" -> "消防通道阻断";
            case "CLEARANCE_FAILED" -> "清场复核未通过";
            case "CERT_MISSING" -> "清场凭证缺失·不可用";
            case "CERT_LATE" -> "清场凭证迟到未生效·不可用";
            case "PENDING_CLEARANCE" -> "清场复核中·暂不可用";
            case "DEPARTURE_PROOF_MISSING" -> "缺离场证明·不可用";
            case "ISOLATION_BLOCKED" -> "隔离距离不足";
            case "OCCUPIED" -> "堆位占用中";
            case "TRANSFER_NOT_APPROVED" -> "转运车未获批";
            default -> "暂不可用";
        };
    }

    // ------------------------------------------------------------------
    // 变更操作
    // ------------------------------------------------------------------

    /** 仅重置危货堆区子域为固定样例（不影响靠泊/回放其它数据）。 */
    public void resetHazmat() {
        dao.resetAndSeed();
    }

    public Clearance registerClearance(String yardCode, String certNo,
                                       LocalDateTime plannedReadyAt,
                                       LocalDateTime certifiedAt,
                                       Boolean reviewPassed) {
        Snapshot snap = dao.loadSnapshot(LocalDateTime.now());
        Yard yard;
        try {
            yard = snap.yard(yardCode);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("堆位不存在: " + yardCode);
        }
        Clearance old = snap.clearances().get(yardCode);
        Clearance c = new Clearance(
                yard.code(),
                certNo != null ? certNo : (old != null ? old.certNo() : "CL-" + yard.code()),
                plannedReadyAt != null ? plannedReadyAt
                        : (old != null ? old.plannedReadyAt() : T1020),
                // 关键：未显式给 certifiedAt 时，不允许默认成计划时间——
                // 是否生效完全由调用方登记的实际出具时间决定
                certifiedAt,
                reviewPassed == null ? (certifiedAt != null) : reviewPassed);
        return dao.saveClearance(c);
    }

    public RoadState setRoad(String code, Boolean clear, String reason,
                             LocalDateTime checkedAt, LocalDateTime restoreAt) {
        Snapshot snap = dao.loadSnapshot(LocalDateTime.now());
        RoadState old = snap.roads().get(code);
        String name = old != null ? old.name() : code;
        boolean isClear = clear == null || clear;
        RoadState r = new RoadState(code, name, isClear,
                checkedAt != null ? checkedAt : LocalDateTime.now(),
                restoreAt,
                reason != null ? reason
                        : (isClear ? "巡查畅通" : (old != null ? old.reason() : "道路阻断")));
        return dao.saveRoad(r);
    }

    public int recordDeparture(String yardCode, LocalDateTime at) {
        Snapshot snap = dao.loadSnapshot(LocalDateTime.now());
        try {
            snap.yard(yardCode);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("堆位不存在: " + yardCode);
        }
        return dao.markDeparture(yardCode, at != null ? at : T1000);
    }

    /** 尝试在某时刻按某策略把某货接进指定（或最优）堆位；不满足则 409 + 结构化理由。 */
    public Map<String, Object> receive(String cargoCode, Strategy strategy,
                                       String yardCode, LocalDateTime at) {
        LocalDateTime t = at != null ? at : DEFAULT_VIEW_AT;
        Strategy st = strategy != null ? strategy : Strategy.TIME_SHARED_CLEARANCE;
        Snapshot snap = dao.loadSnapshot(t);
        Cargo cargo;
        try {
            cargo = snap.cargo(cargoCode == null ? "YI" : cargoCode);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("货物不存在: " + cargoCode);
        }

        YardOption target;
        if (yardCode != null && !yardCode.isBlank()) {
            Yard yard;
            try {
                yard = snap.yard(yardCode);
            } catch (IllegalArgumentException e) {
                throw ApiException.notFound("堆位不存在: " + yardCode);
            }
            target = HazmatEngine.evaluateYard(snap, cargo, yard, st, t);
        } else {
            CargoDecision d = HazmatEngine.decide(snap, cargo.code(), st, t);
            target = d.selected();
            if (target == null) {
                throw ApiException.conflict("HAZMAT_NO_YARD",
                        "在 " + HazmatEngine.hm(t) + " 没有任何堆位可按「" + st + "」接 "
                                + cargo.name()).details(d.options());
            }
        }

        if (target == null || !target.acceptableNow()) {
            throw ApiException.conflict("HAZMAT_REJECTED",
                    yardCode + " 在 " + HazmatEngine.hm(t) + " 不满足接货条件，放行被拒绝")
                    .details(target == null ? List.of() : target.reasons());
        }

        Occupation occ = dao.insertOccupation(target.yardCode(), cargo.code(),
                cargo.hazClass(), "现场接货·" + cargo.name(), "CARGO",
                cargo.tons(), t);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accepted", true);
        m.put("occupation", occ);
        m.put("yardCode", target.yardCode());
        m.put("at", t);
        m.put("board", board(t));
        return m;
    }
}
