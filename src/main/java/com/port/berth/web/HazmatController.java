package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Role;
import com.port.berth.hazmat.Hazmat.Strategy;
import com.port.berth.hazmat.Hazmat.Clearance;
import com.port.berth.hazmat.Hazmat.RoadState;
import com.port.berth.service.HazmatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 危货临时堆区：两种安排对照、堆位可用性、清场凭证/道路/离场证明与接货放行。
 * 除登录与 /api/meta 外 /api/** 均需 Bearer Token；写操作需 DISPATCHER/ADMIN。
 */
@Tag(name = "危货堆区", description = "相容性/隔离距离/清场凭证闸门与两种接货安排对照")
@RestController
@RequestMapping("/api/hazmat")
public class HazmatController {

    private final HazmatService hazmat;

    public HazmatController(HazmatService hazmat) {
        this.hazmat = hazmat;
    }

    @Operation(summary = "危货堆区固定场景事实（堆位/距离/货物/规则/当前凭证与占用）")
    @GetMapping("/scenario")
    public Map<String, Object> scenario() {
        return hazmat.scenario();
    }

    @Operation(summary = "比较固定专用区 vs 清场分时共享 Y1：可接货时间/占用量/被拒理由")
    @GetMapping("/comparison")
    public Map<String, Object> comparison(@RequestParam(required = false) String at) {
        return hazmat.comparison(parseAt(at));
    }

    @Operation(summary = "comparison 的语义别名 /api/hazmat/compare")
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam(required = false) String at) {
        return hazmat.comparison(parseAt(at));
    }

    @Operation(summary = "堆位可用性看板；at 缺省为裁判时刻 2026-11-26T10:20")
    @GetMapping("/yards")
    public Map<String, Object> yards(@RequestParam(required = false) String at) {
        return hazmat.board(parseAt(at));
    }

    @Operation(summary = "单个堆位在某时刻的可用性/闸门/理由")
    @GetMapping("/yards/{code}")
    public Object yard(@PathVariable String code,
                       @RequestParam(required = false) String at) {
        Map<String, Object> board = hazmat.board(parseAt(at));
        return ((java.util.List<?>) board.get("yards")).stream()
                .filter(o -> o instanceof Map<?, ?> m && code.equals(m.get("yardCode")))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("堆位不存在: " + code));
    }

    @Operation(summary = "仅把危货堆区子域重置为固定样例")
    @PostMapping("/reset")
    public Map<String, Object> reset(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        hazmat.resetHazmat();
        return Map.of("reset", true, "at", HazmatService.T1020);
    }

    public record LateCertRequest(LocalDateTime certifiedAt, String certNo) {}

    @Operation(summary = "一键模拟“清场凭证迟到”：默认实际 10:40 才生效（计划仍为 10:20）")
    @PostMapping("/scenario/late-certificate")
    public Clearance lateCertificate(@RequestBody(required = false) LateCertRequest req,
                                    HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        LocalDateTime actual = req != null && req.certifiedAt() != null
                ? req.certifiedAt() : HazmatService.T1040;
        String no = req != null && req.certNo() != null ? req.certNo() : "CL-Y1-LATE";
        return hazmat.registerClearance("Y1", no, HazmatService.T1020, actual, true);
    }

    @Operation(summary = "恢复“准时清场凭证”：10:20 计划且 10:20 实际生效")
    @PostMapping("/scenario/on-time-certificate")
    public Clearance onTimeCertificate(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return hazmat.registerClearance("Y1", "CL-Y1-1020",
                HazmatService.T1020, HazmatService.T1020, true);
    }

    public record ClearanceRequest(String certNo,
                                   LocalDateTime plannedReadyAt,
                                   LocalDateTime certifiedAt,
                                   Boolean reviewPassed) {}

    @Operation(summary = "登记/更新清场凭证（凭证迟到时把 certifiedAt 登记为更晚时间）")
    @PutMapping("/yards/{code}/clearance")
    public Clearance putClearance(@PathVariable String code,
                                 @RequestBody(required = false) ClearanceRequest req,
                                 HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        if (req == null) {
            throw ApiException.badRequest("BODY_REQUIRED", "请提供凭证内容");
        }
        return hazmat.registerClearance(code, req.certNo(), req.plannedReadyAt(),
                req.certifiedAt(), req.reviewPassed());
    }

    public record RoadRequest(Boolean clear, String reason,
                              LocalDateTime checkedAt, LocalDateTime restoreAt) {}

    @Operation(summary = "设置消防通道/转运道路畅通或阻断状态")
    @PostMapping("/roads/{code}")
    public RoadState road(@PathVariable String code,
                          @RequestBody(required = false) RoadRequest req,
                          HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        if (req == null) {
            throw ApiException.badRequest("BODY_REQUIRED", "请提供道路状态");
        }
        return hazmat.setRoad(code, req.clear(), req.reason(),
                req.checkedAt(), req.restoreAt());
    }

    public record DepartureRequest(LocalDateTime departedAt) {}

    @Operation(summary = "登记前批危货实际离场证明")
    @PostMapping("/yards/{code}/departure")
    public Map<String, Object> departure(@PathVariable String code,
                                        @RequestBody(required = false) DepartureRequest req,
                                        HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        LocalDateTime at = req == null ? null : req.departedAt();
        int n = hazmat.recordDeparture(code, at);
        return Map.of("updatedOccupations", n, "yardCode", code,
                "departedAt", at == null ? HazmatService.T1000 : at);
    }

    public record ReceiveRequest(String cargoCode, String strategy,
                                String yardCode, LocalDateTime at) {}

    @Operation(summary = "尝试接货：不满足容量/隔离/凭证/道路条件时返回 409 与结构化拒绝理由")
    @PostMapping("/receiving")
    public Map<String, Object> receive(@RequestBody(required = false) ReceiveRequest req,
                                       HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        String cargoCode = req == null ? "YI" : req.cargoCode();
        String yardCode = req == null ? null : req.yardCode();
        LocalDateTime at = req == null ? null : req.at();
        Strategy st = Strategy.TIME_SHARED_CLEARANCE;
        if (req != null && req.strategy() != null && !req.strategy().isBlank()) {
            try {
                st = Strategy.valueOf(req.strategy().trim());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("BAD_STRATEGY",
                        "strategy 必须是 FIXED_DEDICATED 或 TIME_SHARED_CLEARANCE");
            }
        }
        return hazmat.receive(cargoCode, st, yardCode, at);
    }

    /** 兼容 ?at=2026-11-26T10:20[:ss]，空值用缺省。 */
    static LocalDateTime parseAt(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            if (v.length() == 16) return LocalDateTime.parse(v);
            if (v.length() == 19) return LocalDateTime.parse(v);
            // 兜底：把空格换成 T
            return LocalDateTime.parse(v.replace(' ', 'T'));
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_TIME",
                    "无法解析时间 at=" + s + "，应为 yyyy-MM-ddTHH:mm");
        }
    }
}
