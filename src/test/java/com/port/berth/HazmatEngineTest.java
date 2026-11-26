package com.port.berth;

import com.port.berth.hazmat.Hazmat;
import com.port.berth.hazmat.Hazmat.Cargo;
import com.port.berth.hazmat.Hazmat.CargoDecision;
import com.port.berth.hazmat.Hazmat.Clearance;
import com.port.berth.hazmat.Hazmat.IsolationRule;
import com.port.berth.hazmat.Hazmat.Occupation;
import com.port.berth.hazmat.Hazmat.Reason;
import com.port.berth.hazmat.Hazmat.RoadState;
import com.port.berth.hazmat.Hazmat.Snapshot;
import com.port.berth.hazmat.Hazmat.Strategy;
import com.port.berth.hazmat.Hazmat.Yard;
import com.port.berth.hazmat.Hazmat.YardDistance;
import com.port.berth.hazmat.HazmatEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 危货临时堆区两种安排的可执行规格：
 * 固定专用区 → 乙只能等 Z 到 11:00；清场共享 Y1 → 三证齐全后 10:20 接乙（提早 40 分钟）。
 * 清场凭证迟到/缺失时，Y1 在凭证实际生效前绝不可用。
 */
class HazmatEngineTest {

    static final LocalDateTime D = LocalDateTime.of(2026, 11, 26, 0, 0);
    static final LocalDateTime ARRIVAL = D.withHour(9).withMinute(0);
    static final LocalDateTime T1000 = D.withHour(10);
    static final LocalDateTime T1019 = D.withHour(10).withMinute(19);
    static final LocalDateTime T1020 = D.withHour(10).withMinute(20);
    static final LocalDateTime T1039 = D.withHour(10).withMinute(39);
    static final LocalDateTime T1040 = D.withHour(10).withMinute(40);
    static final LocalDateTime T1100 = D.withHour(11);

    private List<Yard> yards() {
        return List.of(
                new Yard("Y1", "一号临时堆位", bd("20"), "JIA", true, "FIRE-Y1", 1),
                new Yard("Y2", "二号临时堆位", bd("15"), null, false, null, 2),
                new Yard("Z", "Z 远端隔离堆位", bd("15"), "YI", false, null, 3));
    }

    private List<YardDistance> distances() {
        return List.of(
                new YardDistance("Y1", "Y2", 30),
                new YardDistance("Y1", "Z", 55),
                new YardDistance("Y2", "Z", 58));
    }

    private List<Cargo> cargos() {
        return List.of(
                new Cargo("JIA", "甲类危货", "JIA", bd("12"), ARRIVAL, true),
                new Cargo("YI", "乙类危货", "YI", bd("10"), ARRIVAL, true));
    }

    private List<IsolationRule> rules() {
        return List.of(new IsolationRule("JIA", "YI", 50));
    }

    /** 甲在 Y1 09:00-10:00（已离场确认）；Z 既有作业 08:00-11:00 占满 15t。 */
    private List<Occupation> occupations() {
        return List.of(
                new Occupation(1L, "Y1", "JIA", "JIA", "甲货·获批转运车", "CARGO",
                        bd("12"), ARRIVAL, T1000, T1000),
                new Occupation(2L, "Z", null, null, "Z 既有作业（检修倒垛）", "OPERATION",
                        bd("15"), D.withHour(8), T1100, null));
    }

    private Clearance onTimeCert() {
        return new Clearance("Y1", "CL-Y1-1020", T1020, T1020, true);
    }

    private RoadState fireLaneClear() {
        return new RoadState("FIRE-Y1", "Y1 消防通道", true, T1020.minusMinutes(5),
                null, "巡查畅通");
    }

    private Snapshot snapshot(Clearance cert, RoadState road, List<Occupation> occ) {
        Map<String, Clearance> certs = cert == null ? Map.of() : Map.of("Y1", cert);
        Map<String, RoadState> roads = road == null ? Map.of() : Map.of("FIRE-Y1", road);
        return new Snapshot(D, ARRIVAL, yards(), distances(), cargos(), rules(),
                occ == null ? occupations() : occ, certs, roads);
    }

    private Snapshot baseline() {
        return snapshot(onTimeCert(), fireLaneClear(), occupations());
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private Hazmat.YardOption option(CargoDecision d, String yard) {
        return d.options().stream().filter(o -> o.yardCode().equals(yard)).findFirst().orElseThrow();
    }

    private boolean hasCode(List<Reason> rs, String code) {
        return rs.stream().anyMatch(r -> r.code().equals(code));
    }

    @Test
    void fixedDedicated_forcesYiToZ_at1100() {
        Snapshot snap = baseline();
        CargoDecision d = HazmatEngine.decide(snap, "YI", Strategy.FIXED_DEDICATED, ARRIVAL);

        assertEquals("Z", d.assignedYardCode());
        assertEquals(T1100, d.earliestAcceptAt());
        assertEquals(120, d.waitMinutesFromArrival());

        var y1 = option(d, "Y1");
        assertTrue(hasCode(y1.reasons(), "ZONE_DEDICATED"), "Y1 应为甲类固定专用区");
        assertFalse(y1.eligible());

        var y2 = option(d, "Y2");
        assertTrue(hasCode(y2.reasons(), "YARD_NOT_DESIGNATED"),
                "Y2 未指定为乙类专用/共享堆位");
        assertTrue(hasCode(y2.reasons(), "ISOLATION_DISTANCE_INSUFFICIENT"),
                "09:00 时 Y2 距 Y1 甲货仅 30m<50m，隔离不足");

        var z = option(d, "Z");
        assertTrue(hasCode(z.reasons(), "YARD_OCCUPIED_UNTIL"));
        assertEquals(T1100, z.earliestAcceptAt());
    }

    @Test
    void timeSharedY1_acceptsYiAt1020_andSaves40Minutes() {
        Snapshot snap = baseline();
        CargoDecision shared = HazmatEngine.decide(snap, "YI",
                Strategy.TIME_SHARED_CLEARANCE, ARRIVAL);
        CargoDecision fixed = HazmatEngine.decide(snap, "YI",
                Strategy.FIXED_DEDICATED, ARRIVAL);

        assertEquals("Y1", shared.assignedYardCode());
        assertEquals(T1020, shared.earliestAcceptAt());
        assertEquals(80, shared.waitMinutesFromArrival());

        long saved = java.time.Duration.between(
                shared.earliestAcceptAt(), fixed.earliestAcceptAt()).toMinutes();
        assertEquals(40, saved, "共享 Y1 应比死等 Z 提早 40 分钟");

        // 边界：10:19 仍不可接，10:20 整凭证生效即可接
        var y1At1019 = HazmatEngine.evaluateYard(snap, snap.cargo("YI"), snap.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1019);
        assertFalse(y1At1019.acceptableNow());
        assertTrue(hasCode(y1At1019.reasons(), "CLEARANCE_CERT_PENDING"));

        var y1At1020 = HazmatEngine.evaluateYard(snap, snap.cargo("YI"), snap.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1020);
        assertTrue(y1At1020.acceptableNow(), "10:20 三证齐全，Y1 应可接乙货");

        // 三证闸门齐全
        var gateCodes = y1At1020.gates().stream().map(Hazmat.Gate::code).toList();
        assertTrue(gateCodes.contains("DEPARTURE"));
        assertTrue(gateCodes.contains("CLEARANCE_CERT"));
        assertTrue(gateCodes.contains("FIRE_LANE"));
        assertTrue(y1At1020.gates().stream().filter(g ->
                g.code().equals("DEPARTURE") || g.code().equals("CLEARANCE_CERT")
                        || g.code().equals("FIRE_LANE")).allMatch(Hazmat.Gate::satisfied));
    }

    @Test
    void lateCertificate_hidesY1UntilActualCertTime() {
        // 凭证迟到：计划 10:20，实际 10:40 才生效
        Clearance late = new Clearance("Y1", "CL-Y1-LATE", T1020, T1040, true);
        Snapshot snap = snapshot(late, fireLaneClear(), occupations());

        var at1020 = HazmatEngine.evaluateYard(snap, snap.cargo("YI"), snap.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1020);
        assertFalse(at1020.acceptableNow(), "越过计划 10:20 但凭证迟到未生效，不得可用");
        assertTrue(hasCode(at1020.reasons(), "CLEARANCE_CERT_LATE"));

        var at1039 = HazmatEngine.evaluateYard(snap, snap.cargo("YI"), snap.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1039);
        assertFalse(at1039.acceptableNow());

        var at1040 = HazmatEngine.evaluateYard(snap, snap.cargo("YI"), snap.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1040);
        assertTrue(at1040.acceptableNow(), "凭证 10:40 生效后才可用");

        CargoDecision d = HazmatEngine.decide(snap, "YI",
                Strategy.TIME_SHARED_CLEARANCE, ARRIVAL);
        assertEquals(T1040, d.earliestAcceptAt(), "最早可接时间应跟随实际凭证时间");
        assertEquals("Y1", d.assignedYardCode(), "10:40 的 Y1 仍早于 11:00 的 Z");
    }

    @Test
    void missingCertificate_neverAvailable_evenAfterPlannedTime() {
        Snapshot noCert = snapshot(null, fireLaneClear(), occupations());
        var y1 = HazmatEngine.evaluateYard(noCert, noCert.cargo("YI"), noCert.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1100);
        assertFalse(y1.acceptableNow(), "没有清场凭证，任何时刻都不得显示可用");
        assertTrue(hasCode(y1.reasons(), "CLEARANCE_CERT_REQUIRED"));
        assertNull(y1.earliestAcceptAt(), "凭证缺失给不出确定最早时间");
    }

    @Test
    void blockedFireLane_rejectsEvenWithCert() {
        RoadState blocked = new RoadState("FIRE-Y1", "Y1 消防通道", false, T1020,
                null, "通道被临时堆垛占用，恢复时间待定");
        Snapshot snap = snapshot(onTimeCert(), blocked, occupations());
        var y1 = HazmatEngine.evaluateYard(snap, snap.cargo("YI"), snap.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1020);
        assertFalse(y1.acceptableNow());
        assertTrue(hasCode(y1.reasons(), "FIRE_LANE_BLOCKED"));
        assertNull(y1.earliestAcceptAt(), "恢复时刻未知时不能承诺可接时间");
    }

    @Test
    void missingDepartureProof_blocksAfterPlannedEnd() {
        // 甲计划 10:00 结束，但没有实际离场确认（departedAt=null）
        List<Occupation> occ = List.of(
                new Occupation(1L, "Y1", "JIA", "JIA", "甲货·获批转运车", "CARGO",
                        bd("12"), ARRIVAL, T1000, null),
                occupations().get(1));
        Snapshot snap = snapshot(onTimeCert(), fireLaneClear(), occ);

        var at0959 = HazmatEngine.evaluateYard(snap, snap.cargo("YI"), snap.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1000.minusMinutes(1));
        assertTrue(hasCode(at0959.reasons(), "YARD_OCCUPIED_UNTIL"), "未到 10:00 先表现为占用");

        var at1000 = HazmatEngine.evaluateYard(snap, snap.cargo("YI"), snap.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1000);
        assertTrue(hasCode(at1000.reasons(), "DEPARTURE_PROOF_REQUIRED"),
                "过了计划结束点但无离场证明，必须拒绝");
        assertFalse(at1000.acceptableNow());
    }

    @Test
    void overCapacityCargo_isTerminalRejection() {
        Cargo big = new Cargo("BIG", "超重货", "YI", bd("21"), ARRIVAL, true);
        Snapshot snap = baseline();
        var y1 = HazmatEngine.evaluateYard(snap, big, snap.yard("Y1"),
                Strategy.TIME_SHARED_CLEARANCE, T1100);
        assertTrue(hasCode(y1.reasons(), "CAPACITY_EXCEEDED"));
        assertFalse(y1.eligible());
    }
}
