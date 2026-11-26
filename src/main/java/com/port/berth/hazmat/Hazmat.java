package com.port.berth.hazmat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 危险货物临时堆区——领域模型集合。
 *
 * <p>固定样例（2026-11-26 上午）：Y1/Y2/Z 容量 20/15/15 吨；Y1-Y2 相距 30m，
 * Z 距两者均 &gt;50m；甲、乙两类危货隔离距离 50m。甲货 12t 先放 Y1，10:00 转运出场，
 * Y1 清场复核计划 10:20 完成；Z 被既有作业占到 11:00。
 */
public final class Hazmat {

    private Hazmat() {}

    /** 接货安排策略 */
    public enum Strategy {
        /** 固定专用区：堆位按货类专用，不跨类分时复用，放行判断直接但乙只能等其专用 Z 区 */
        FIXED_DEDICATED,
        /** 清场分时共享：Y1 在“甲离场 + 清场凭证生效 + 消防通道畅通”三证齐全后可接乙 */
        TIME_SHARED_CLEARANCE
    }

    /** 堆位物理/专用属性 */
    public record Yard(
            String code,
            String name,
            BigDecimal capacityT,
            /** 固定专用货类（JIA/YI/BING）；null 表示通用 */
            String dedicatedClass,
            /** 是否允许在清场凭证闭环后跨类分时共享 */
            boolean sharedClearance,
            /** 进出该堆位必须畅通的道路/消防通道编码 */
            String accessRoadCode,
            int sortNo) {}

    public record YardDistance(String yardA, String yardB, int distanceM) {}

    public record Cargo(
            String code,
            String name,
            String hazClass,
            BigDecimal tons,
            LocalDateTime arrivedAt,
            /** 转运车是否已获监管/港区批准 */
            boolean transferApproved) {}

    /** 两类危货之间的最低隔离距离（米） */
    public record IsolationRule(String classA, String classB, int minDistanceM) {}

    public record Occupation(
            Long id,
            String yardCode,
            String cargoCode,
            String hazClass,
            String ownerLabel,
            /** CARGO=危货堆存；OPERATION=既有作业/检修占用 */
            String kind,
            BigDecimal tons,
            LocalDateTime startAt,
            LocalDateTime endAt,
            /** 实际离场证明时间；为空表示尚无离场凭证 */
            LocalDateTime departedAt) {

        /** 实际释放时刻：优先以离场证明确认为准，否则用计划结束时间；都没有则无限期占用 */
        public LocalDateTime effectiveEnd() {
            if (departedAt != null) return departedAt;
            return endAt != null ? endAt : LocalDateTime.MAX;
        }
    }

    /**
     * 清场凭证。凭证是否让堆位恢复可用，只看 reviewPassed 与 certifiedAt（实际出具/送达时间），
     * plannedReadyAt 只是计划复核完成时间——凭证迟到时，越过计划时间也不得显示可用。
     */
    public record Clearance(
            String yardCode,
            String certNo,
            LocalDateTime plannedReadyAt,
            LocalDateTime certifiedAt,
            boolean reviewPassed) {

        public boolean issued() {
            return reviewPassed && certifiedAt != null;
        }

        public boolean effectiveAt(LocalDateTime t) {
            return issued() && !certifiedAt.isAfter(t);
        }
    }

    public record RoadState(
            String code,
            String name,
            boolean clear,
            LocalDateTime checkedAt,
            /** 已知恢复畅通时间；为空且 clear=false 表示恢复时间未知 */
            LocalDateTime restoreAt,
            String reason) {}

    /** 引擎计算所需的某一时刻全量事实快照（由 DAO 或单测构造） */
    public record Snapshot(
            LocalDateTime scenarioDate,
            LocalDateTime decisionAt,
            List<Yard> yards,
            List<YardDistance> distances,
            List<Cargo> cargos,
            List<IsolationRule> rules,
            List<Occupation> occupations,
            Map<String, Clearance> clearances,
            Map<String, RoadState> roads) {

        public Yard yard(String code) {
            return yards.stream().filter(y -> y.code().equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知堆位 " + code));
        }

        public Cargo cargo(String code) {
            return cargos.stream().filter(c -> c.code().equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知货物 " + code));
        }
    }

    /** 阻塞原因 */
    public record Reason(
            String code,
            String message,
            /** 该原因预计解除的确定时刻；null 且 terminal=false 表示等待外部凭证、时刻未知 */
            LocalDateTime until,
            /** true=在当前策略/物理条件下该堆位永远不能接该货（如专用区不符、能力不足） */
            boolean terminal,
            Map<String, Object> detail) {}

    /** 放行闸门（用于“三证”清单展示） */
    public record Gate(
            String code,
            String label,
            boolean satisfied,
            /** OK / WAIT / BLOCK / NA */
            String state,
            LocalDateTime at,
            String detail) {}

    /** 某堆位对某货物在某策略/时刻下的评估结果 */
    public record YardOption(
            String yardCode,
            String yardName,
            boolean eligible,
            boolean acceptableNow,
            LocalDateTime evaluatedAt,
            LocalDateTime earliestAcceptAt,
            BigDecimal capacityT,
            BigDecimal occupiedTNow,
            BigDecimal remainingTNow,
            List<Gate> gates,
            List<Reason> reasons) {}

    /** 某货物在某策略下的整体接货决策 */
    public record CargoDecision(
            String cargoCode,
            String cargoName,
            Strategy strategy,
            LocalDateTime evaluatedAt,
            LocalDateTime arrivedAt,
            Integer waitMinutesFromArrival,
            String assignedYardCode,
            LocalDateTime earliestAcceptAt,
            boolean acceptableNow,
            YardOption selected,
            List<YardOption> options) {}
}
