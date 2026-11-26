package com.port.berth.hazmat;

import com.port.berth.hazmat.Hazmat.Cargo;
import com.port.berth.hazmat.Hazmat.CargoDecision;
import com.port.berth.hazmat.Hazmat.Clearance;
import com.port.berth.hazmat.Hazmat.Gate;
import com.port.berth.hazmat.Hazmat.IsolationRule;
import com.port.berth.hazmat.Hazmat.Occupation;
import com.port.berth.hazmat.Hazmat.Reason;
import com.port.berth.hazmat.Hazmat.RoadState;
import com.port.berth.hazmat.Hazmat.Snapshot;
import com.port.berth.hazmat.Hazmat.Strategy;
import com.port.berth.hazmat.Hazmat.Yard;
import com.port.berth.hazmat.Hazmat.YardOption;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 危货临时堆区接货判定纯函数引擎：容量、货类专用、隔离距离、既有占用、
 * 转运批准、离场证明、清场凭证与消防通道闸门。不依赖 Spring / 数据库，便于单测。
 *
 * <p>关键不变量：堆位“可用”必须在查询时刻同时满足全部闸门；清场凭证以实际
 * certifiedAt 为准，凭证迟到/缺失时，即使已越过计划清场时间也绝不返回可用。
 */
public final class HazmatEngine {

    private HazmatEngine() {}

    public static final String C_JIA = "JIA";
    public static final String C_YI = "YI";

    // ------------------------------------------------------------------
    // 基础查询
    // ------------------------------------------------------------------

    /** 两块堆位间距离；未配置返回 null（调用方按“无法证明满足隔离”处理）。 */
    public static Integer distanceM(Snapshot snap, String a, String b) {
        if (a.equals(b)) return 0;
        for (var d : snap.distances()) {
            if ((d.yardA().equals(a) && d.yardB().equals(b))
                    || (d.yardA().equals(b) && d.yardB().equals(a))) {
                return d.distanceM();
            }
        }
        return null;
    }

    /** 两类货要求的隔离距离（取匹配规则最大值，无规则为 0）。 */
    public static int requiredIsolationM(Snapshot snap, String classA, String classB) {
        if (classA == null || classB == null || classA.equals(classB)) return 0;
        int req = 0;
        for (IsolationRule r : snap.rules()) {
            boolean pair = (r.classA().equals(classA) && r.classB().equals(classB))
                    || (r.classA().equals(classB) && r.classB().equals(classA));
            if (pair) req = Math.max(req, r.minDistanceM());
        }
        return req;
    }

    public static boolean activeAt(Occupation o, LocalDateTime t) {
        LocalDateTime s = o.startAt();
        LocalDateTime e = o.effectiveEnd();
        boolean started = s == null || !t.isBefore(s);
        return started && t.isBefore(e); // 半开区间 [start, end)
    }

    public static BigDecimal occupiedTons(Snapshot snap, String yardCode, LocalDateTime t) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Occupation o : snap.occupations()) {
            if (o.yardCode().equals(yardCode) && activeAt(o, t) && o.tons() != null) {
                sum = sum.add(o.tons());
            }
        }
        return sum;
    }

    private static List<Occupation> activeOccupations(Snapshot snap, String yardCode,
                                                      LocalDateTime t) {
        List<Occupation> out = new ArrayList<>();
        for (Occupation o : snap.occupations()) {
            if (o.yardCode().equals(yardCode) && activeAt(o, t)) out.add(o);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 某一时刻的全部阻塞原因
    // ------------------------------------------------------------------

    public static List<Reason> blockersAt(Snapshot snap, Cargo cargo, Yard yard,
                                          Strategy strategy, LocalDateTime t) {
        List<Reason> reasons = new ArrayList<>();

        // 1) 物理容量（即使堆位全空也放不下 → 终局不可用）
        if (cargo.tons().compareTo(yard.capacityT()) > 0) {
            reasons.add(new Reason("CAPACITY_EXCEEDED",
                    "货物 " + cargo.tons().stripTrailingZeros().toPlainString()
                            + "t 超过 " + yard.code() + " 额定容量 "
                            + yard.capacityT().stripTrailingZeros().toPlainString() + "t",
                    null, true, Map.of(
                            "requiredT", cargo.tons(), "capacityT", yard.capacityT())));
        }

        boolean classMatch = yard.dedicatedClass() != null
                && yard.dedicatedClass().equals(cargo.hazClass());
        boolean handoverShare = strategy == Strategy.TIME_SHARED_CLEARANCE
                && !classMatch && yard.sharedClearance();

        // 2) 货类专用 / 共享策略闸门
        if (!classMatch && !handoverShare) {
            if (yard.dedicatedClass() != null) {
                reasons.add(new Reason("ZONE_DEDICATED",
                        yard.code() + " 是「" + classLabel(yard.dedicatedClass())
                                + "」固定专用区；"
                                + (strategy == Strategy.FIXED_DEDICATED
                                        ? "固定专用安排下不跨货类复用，即使物理清空也不能改接"
                                        : "该堆位未纳入“清场后分时共享”，不能跨类接")
                                + classLabel(cargo.hazClass()),
                        null, true, Map.of("dedicatedClass", yard.dedicatedClass(),
                                "requestedClass", cargo.hazClass())));
            } else {
                reasons.add(new Reason("YARD_NOT_DESIGNATED",
                        yard.code() + " 未被指定为「" + classLabel(cargo.hazClass())
                                + "」固定专用堆位，也不具备清场分时共享/获批转运道路条件；"
                                + "两种安排下均不能作为" + classLabel(cargo.hazClass())
                                + "的放行堆位",
                        null, true, Map.of("requestedClass", cargo.hazClass())));
            }
        }

        // 3) 转运车批准（全局资质）
        if (!cargo.transferApproved()) {
            reasons.add(new Reason("TRANSFER_NOT_APPROVED",
                    "承担转运的车辆尚未获得获批转运资质，不得进场接货",
                    null, false, Map.of()));
        }

        // 4) 本堆位既有占用 + 容量叠加（顺序共享时，前一批未走会一直顶到其释放时刻）
        List<Occupation> active = activeOccupations(snap, yard.code(), t);
        BigDecimal occupied = BigDecimal.ZERO;
        LocalDateTime earliestRelease = null;
        for (Occupation o : active) {
            if (o.tons() != null) occupied = occupied.add(o.tons());
            LocalDateTime e = o.effectiveEnd();
            if (!LocalDateTime.MAX.equals(e)
                    && (earliestRelease == null || e.isBefore(earliestRelease))) {
                earliestRelease = e;
            }
        }
        if (occupied.add(cargo.tons()).compareTo(yard.capacityT()) > 0) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("occupiedT", occupied);
            detail.put("incomingT", cargo.tons());
            detail.put("capacityT", yard.capacityT());
            active.stream().max(Comparator.comparing(Occupation::startAt,
                    Comparator.nullsFirst(Comparator.naturalOrder())))
                    .ifPresent(o -> detail.put("holder",
                            o.ownerLabel() != null ? o.ownerLabel() : o.cargoCode()));
            String code = earliestRelease != null ? "YARD_OCCUPIED_UNTIL" : "YARD_OCCUPIED";
            String msg = yard.code() + " 当前已占用 "
                    + occupied.stripTrailingZeros().toPlainString() + "t，接入 "
                    + cargo.tons().stripTrailingZeros().toPlainString()
                    + "t 后超过/占满容量 " + yard.capacityT().stripTrailingZeros().toPlainString()
                    + "t（持有方：" + detail.getOrDefault("holder", "既有作业") + "）";
            if (earliestRelease != null) {
                msg += "，最早 " + hm(earliestRelease) + " 释放";
            }
            reasons.add(new Reason(code, msg, earliestRelease, false, detail));
        }

        // 5) 隔离距离：与其它堆位上“当时仍在”的不相容危货
        for (Occupation o : snap.occupations()) {
            if (o.yardCode().equals(yard.code()) || !activeAt(o, t)) continue;
            int req = requiredIsolationM(snap, cargo.hazClass(), o.hazClass());
            if (req <= 0) continue;
            Integer dist = distanceM(snap, yard.code(), o.yardCode());
            if (dist != null && dist >= req) continue;

            LocalDateTime until = o.effectiveEnd();
            LocalDateTime untilOrNull = LocalDateTime.MAX.equals(until) ? null : until;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("otherYard", o.yardCode());
            detail.put("distanceM", dist);
            detail.put("requiredM", req);
            detail.put("holder", o.ownerLabel() != null ? o.ownerLabel() : o.cargoCode());
            reasons.add(new Reason("ISOLATION_DISTANCE_INSUFFICIENT",
                    yard.code() + " 与 " + o.yardCode() + " 实际距离 "
                            + (dist == null ? "未知" : dist + "m")
                            + "，小于甲乙类要求的 " + req + "m 隔离距离；"
                            + o.yardCode() + " 上的 "
                            + detail.get("holder") + " 未离场"
                            + (untilOrNull != null ? "，须等到 " + hm(untilOrNull) : ""),
                    untilOrNull, false, detail));
        }

        // 6) 清场分时共享的三项证明闸门
        if (handoverShare) {
            Occupation pred = predecessorOccupation(snap, yard);

            // 6a) 甲货实际离场证明：计划结束时刻已过却没有 departedAt → 不能证明离场
            if (pred != null && pred.departedAt() == null && !t.isBefore(pred.effectiveEnd())) {
                reasons.add(new Reason("DEPARTURE_PROOF_REQUIRED",
                        "缺少前批货物（"
                                + (pred.cargoCode() != null ? pred.cargoCode()
                                        : classLabel(yard.dedicatedClass()))
                                + "）的实际离场确认，不能证明 " + yard.code() + " 已清空",
                        null, false, Map.of("yard", yard.code())));
            }

            // 6b) 清场凭证：只认真实 certifiedAt
            Clearance cl = snap.clearances().get(yard.code());
            if (cl == null || !cl.issued()) {
                boolean failed = cl != null && !cl.reviewPassed();
                Map<String, Object> certDetail = new LinkedHashMap<>();
                certDetail.put("yard", yard.code());
                certDetail.put("plannedReadyAt", cl == null ? null : cl.plannedReadyAt());
                reasons.add(new Reason(
                        failed ? "CLEARANCE_REVIEW_FAILED" : "CLEARANCE_CERT_REQUIRED",
                        failed
                                ? yard.code() + " 清场复核未通过，不得显示为可用"
                                : yard.code() + " 清场凭证尚未出具/登记；凭证缺失时即使已过计划清场时间，"
                                        + "堆位也不得显示为可用",
                        null, false, certDetail));
            } else if (t.isBefore(cl.certifiedAt())) {
                boolean late = cl.plannedReadyAt() != null
                        && !t.isBefore(cl.plannedReadyAt());
                String code = late ? "CLEARANCE_CERT_LATE" : "CLEARANCE_CERT_PENDING";
                String msg = late
                        ? "清场凭证迟到：计划 " + hm(cl.plannedReadyAt())
                                + " 复核完成，但凭证实际 " + hm(cl.certifiedAt())
                                + " 才生效；在凭证生效前 " + yard.code() + " 不得显示为可用"
                        : yard.code() + " 清场复核/凭证办理中，凭证 "
                                + hm(cl.certifiedAt()) + " 后生效";
                Map<String, Object> lateDetail = new LinkedHashMap<>();
                lateDetail.put("plannedReadyAt", cl.plannedReadyAt());
                lateDetail.put("certifiedAt", cl.certifiedAt());
                lateDetail.put("certNo", cl.certNo());
                reasons.add(new Reason(code, msg, cl.certifiedAt(), false, lateDetail));
            }

            // 6c) 消防通道 / 转运道路畅通
            RoadState road = yard.accessRoadCode() == null ? null
                    : snap.roads().get(yard.accessRoadCode());
            if (road != null && !road.clear()) {
                Map<String, Object> roadDetail = new LinkedHashMap<>();
                roadDetail.put("roadCode", road.code());
                roadDetail.put("restoreAt", road.restoreAt());
                reasons.add(new Reason("FIRE_LANE_BLOCKED",
                        "进出 " + yard.code() + " 的消防通道/转运道路「" + road.name()
                                + "」不畅通：" + road.reason()
                                + "；不能证明消防通道畅通前不得接货",
                        road.restoreAt(), false, roadDetail));
            }
        }

        return reasons;
    }

    /** 该堆位上属于其专用货类的最近一批危货（共享切换的“前批”）。 */
    private static Occupation predecessorOccupation(Snapshot snap, Yard yard) {
        return snap.occupations().stream()
                .filter(o -> o.yardCode().equals(yard.code()))
                .filter(o -> "CARGO".equals(o.kind()))
                .filter(o -> yard.dedicatedClass() == null
                        || yard.dedicatedClass().equals(o.hazClass()))
                .max(Comparator.comparing(Occupation::startAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // 最早可接时间：在事实时间轴上迭代到所有闸门同时满足
    // ------------------------------------------------------------------

    public static YardOption evaluateYard(Snapshot snap, Cargo cargo, Yard yard,
                                          Strategy strategy, LocalDateTime at) {
        List<Reason> now = blockersAt(snap, cargo, yard, strategy, at);
        boolean terminalNow = now.stream().anyMatch(Reason::terminal);
        boolean acceptableNow = now.isEmpty();

        LocalDateTime earliest = null;
        boolean eligible = !terminalNow;
        if (!terminalNow) {
            LocalDateTime t = at;
            for (int i = 0; i < 64; i++) {
                List<Reason> rs = blockersAt(snap, cargo, yard, strategy, t);
                if (rs.isEmpty()) { earliest = t; break; }
                if (rs.stream().anyMatch(Reason::terminal)) { eligible = false; earliest = null; break; }
                // 存在“等待外部凭证、解除时刻未知”的原因 → 给不出确定的最早时间
                if (rs.stream().anyMatch(r -> r.until() == null)) { earliest = null; break; }
                LocalDateTime next = rs.stream().map(Reason::until)
                        .min(LocalDateTime::compareTo).orElse(null);
                if (next == null || !next.isAfter(t)) { earliest = null; break; }
                t = next;
            }
        }

        BigDecimal occupiedNow = occupiedTons(snap, yard.code(), at);
        BigDecimal remaining = yard.capacityT().subtract(occupiedNow);

        List<Gate> gates = gates(snap, cargo, yard, strategy, at);

        return new YardOption(yard.code(), yard.name(), eligible, acceptableNow, at,
                earliest, yard.capacityT(), occupiedNow, remaining, gates, now);
    }

    /** 放行闸门清单（重点呈现共享 Y1 的“离场 / 清场凭证 / 消防通道”三证）。 */
    public static List<Gate> gates(Snapshot snap, Cargo cargo, Yard yard,
                                   Strategy strategy, LocalDateTime at) {
        List<Gate> gates = new ArrayList<>();

        boolean capacityPhysicalOk = cargo.tons().compareTo(yard.capacityT()) <= 0;
        gates.add(new Gate("CAPACITY", "容量校验（"
                + cargo.tons().stripTrailingZeros().toPlainString() + "t ≤ "
                + yard.capacityT().stripTrailingZeros().toPlainString() + "t）",
                capacityPhysicalOk, capacityPhysicalOk ? "OK" : "BLOCK", null,
                capacityPhysicalOk ? "容量满足" : "超过堆位额定容量"));

        boolean classMatch = yard.dedicatedClass() != null
                && yard.dedicatedClass().equals(cargo.hazClass());
        boolean handoverShare = strategy == Strategy.TIME_SHARED_CLEARANCE
                && !classMatch && yard.sharedClearance();
        if (classMatch) {
            gates.add(new Gate("ZONE_CLASS", "货类与专用区一致", true, "OK", null,
                    yard.code() + " 为" + classLabel(cargo.hazClass()) + "专用区"));
        } else if (!handoverShare) {
            gates.add(new Gate("ZONE_CLASS", "固定专用/指定堆位不跨类", false, "BLOCK", null,
                    yard.dedicatedClass() != null
                            ? yard.code() + " 是" + classLabel(yard.dedicatedClass()) + "专用区"
                            : yard.code() + " 未指定为该货类专用/共享堆位"));
        } else {
            gates.add(new Gate("SHARE_POLICY", "纳入清场后分时共享", true, "OK", null,
                    "专用限制以下列三项证明为解除条件"));

            Occupation pred = predecessorOccupation(snap, yard);
            boolean departed = pred != null && pred.departedAt() != null
                    && !pred.departedAt().isAfter(at);
            gates.add(new Gate("DEPARTURE", "前批货实际离场确认", departed,
                    departed ? "OK" : "WAIT",
                    pred == null ? null : pred.departedAt(),
                    departed ? "离场证明 @ " + hm(pred.departedAt())
                            : (pred != null && pred.departedAt() == null
                                    ? "尚无离场确认" : "等待 " + hm(pred.effectiveEnd()))));

            Clearance cl = snap.clearances().get(yard.code());
            boolean certOk = cl != null && cl.effectiveAt(at);
            String certState;
            String certDetail;
            if (cl == null || !cl.issued()) {
                certState = "BLOCK";
                certDetail = cl != null && !cl.reviewPassed() ? "复核未通过" : "凭证未出具/登记";
            } else if (certOk) {
                certState = "OK";
                certDetail = "凭证 " + cl.certNo() + " 已于 " + hm(cl.certifiedAt()) + " 生效";
            } else {
                certState = "WAIT";
                certDetail = "凭证实际 " + hm(cl.certifiedAt()) + " 才生效（计划 "
                        + (cl.plannedReadyAt() == null ? "-" : hm(cl.plannedReadyAt())) + "）";
            }
            gates.add(new Gate("CLEARANCE_CERT", "清场复核凭证生效", certOk, certState,
                    cl == null ? null : cl.certifiedAt(), certDetail));

            RoadState road = yard.accessRoadCode() == null ? null
                    : snap.roads().get(yard.accessRoadCode());
            boolean roadOk = road == null || road.clear();
            gates.add(new Gate("FIRE_LANE", "消防通道/转运道路畅通", roadOk,
                    roadOk ? "OK" : "BLOCK", road == null ? null : road.checkedAt(),
                    road == null ? "未配置道路状态"
                            : (road.clear() ? "畅通（核查于 " + hm(road.checkedAt()) + "）"
                                    : "阻断：" + road.reason())));
        }

        // 隔离闸门（取最严的一条不相容相邻堆位）
        Reason iso = blockersAt(snap, cargo, yard, strategy, at).stream()
                .filter(r -> r.code().equals("ISOLATION_DISTANCE_INSUFFICIENT"))
                .findFirst().orElse(null);
        gates.add(new Gate("ISOLATION", "与不相容危货隔离 ≥ "
                + requiredIsolationM(snap, cargo.hazClass(), HazmatEngine.C_JIA)
                + "m（甲乙类）", iso == null, iso == null ? "OK" : "BLOCK",
                iso == null ? null : iso.until(),
                iso == null ? "当前时刻隔离距离满足" : iso.message()));

        return gates;
    }

    // ------------------------------------------------------------------
    // 货物级决策：在所有堆位中选最早可接者
    // ------------------------------------------------------------------

    public static CargoDecision decide(Snapshot snap, String cargoCode,
                                       Strategy strategy, LocalDateTime at) {
        Cargo cargo = snap.cargo(cargoCode);
        List<YardOption> options = new ArrayList<>();
        for (Yard y : snap.yards()) {
            options.add(evaluateYard(snap, cargo, y, strategy, at));
        }
        options.sort(Comparator.comparing(YardOption::yardCode));

        YardOption selected = options.stream()
                .filter(YardOption::eligible)
                .filter(o -> o.earliestAcceptAt() != null)
                .min(Comparator.comparing(YardOption::earliestAcceptAt))
                .orElse(null);

        LocalDateTime earliest = selected == null ? null : selected.earliestAcceptAt();
        String assigned = selected == null ? null : selected.yardCode();
        boolean acceptableNow = selected != null && selected.acceptableNow();

        Integer wait = null;
        if (earliest != null && cargo.arrivedAt() != null) {
            wait = (int) Duration.between(cargo.arrivedAt(), earliest).toMinutes();
        }

        return new CargoDecision(cargo.code(), cargo.name(), strategy, at,
                cargo.arrivedAt(), wait, assigned, earliest, acceptableNow,
                selected, options);
    }

    // ------------------------------------------------------------------
    // 展示工具
    // ------------------------------------------------------------------

    public static String hm(LocalDateTime t) {
        if (t == null || LocalDateTime.MAX.equals(t)) return "?";
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    public static String classLabel(String hazClass) {
        if (hazClass == null) return "普通";
        return switch (hazClass) {
            case C_JIA -> "甲类";
            case C_YI -> "乙类";
            case "BING" -> "丙类";
            default -> hazClass;
        };
    }

    public static double pct(BigDecimal tons, BigDecimal cap) {
        if (cap == null || cap.signum() == 0) return 0;
        return tons.multiply(BigDecimal.valueOf(100))
                .divide(cap, 0, RoundingMode.HALF_UP).intValue();
    }
}
