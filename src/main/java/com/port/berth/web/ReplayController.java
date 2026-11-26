package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Role;
import com.port.berth.service.HazmatReplayService;
import com.port.berth.service.ReplayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**一键回放三种顺序、并发竞速、策略对照、扰动演练与危货堆区对照*/
@RestController
@RequestMapping("/api/replays")
public class ReplayController {

    private final ReplayService replayService;
    private final HazmatReplayService hazmatReplayService;

    @org.springframework.beans.factory.annotation.Autowired
    public ReplayController(ReplayService replayService,
                           HazmatReplayService hazmatReplayService) {
        this.replayService = replayService;
        this.hazmatReplayService = hazmatReplayService;
    }

    /**兼容既有单参构造（旧测试/调用）。*/
    public ReplayController(ReplayService replayService) {
        this(replayService, null);
    }

    @PostMapping("/orderings")
    public Map<String, Object> orderings(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return replayService.runOrderings();
    }

    @PostMapping("/disruptions")
    public Map<String, Object> disruptions(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return replayService.runDisruptions();
    }

    @PostMapping("/hazmat")
    public Map<String, Object> hazmat(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return hazmatReplayService.runHazmat();
    }

    @PostMapping("/full")
    public Map<String, Object> full(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        Map<String, Object> all = new LinkedHashMap<>();
        Map<String, Object> orderings = replayService.runOrderings();
        Map<String, Object> disruptions = replayService.runDisruptions();
        all.put("orderings", orderings);
        all.put("disruptions", disruptions);
        boolean pass = Boolean.TRUE.equals(orderings.get("allPassed"))
                && Boolean.TRUE.equals(disruptions.get("allPassed"));
        // 兼容单参构造下未装配危货回放的极端情况
        if (hazmatReplayService != null) {
            Map<String, Object> hazmat = hazmatReplayService.runHazmat();
            all.put("hazmat", hazmat);
            pass &= Boolean.TRUE.equals(hazmat.get("allPassed"));
        }
        all.put("allPassed", pass);
        return all;
    }
}
