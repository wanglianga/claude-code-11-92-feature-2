# 港口靠泊协同服务（Berth Coordination）

船代提交船次（船长/吃水/货类/ETA 区间/作业时长/岸桥数），码头调度枚举并比较候选靠泊计划，
对 **泊位 + 引航员 + 拖轮 + 岸桥整组资源** 做暂留或原子确认；确认后的计划才下发引航、拖轮、岸桥班组。
系统在**整个靠泊操纵与装卸窗口**校验吃水下净空（UKC ≥ 1m）、货类限制、人员值班/交接、
设备工作区间/转场/周转，并提供潮汐修订、岸桥故障、改靠、ETA 迟到的影响分析与版本化重排；
已完成的引航/装卸记录在应用层与数据库触发器双层保证不可覆盖。

> **特性 92-feature-2（危货临时堆区接货对照）**：新增独立的危货堆区子域，对 **固定专用区** 与
> **按清场时间共享 Y1** 两种安排，给出乙货的**可接货时间、各堆位占用量与结构化被拒理由**，
> 并把“**清场凭证迟到/缺失时，堆位绝不能显示为可用**”实现为引擎硬不变量、REST 闸门与 29 条回放断言。
> 详见第 11 节。

- 后端：Java（`--release 21`，已在预装 **JDK 25.0.1** 上构建运行）、Spring Boot 3.5.7、Spring JDBC、PostgreSQL
- 前端：`src/main/resources/static/` 原生 HTML/CSS/JS 单页（无需 Node 构建）
- 接口文档：springdoc-openapi，`/swagger-ui.html`
- 一键启动：`Dockerfile` + `docker-compose.yml`（PostgreSQL 18）

---

## 1. 题目场景与内置样例

固定样例时间为 **2026-11-26 晚**，重置接口/回放会反复恢复该数据：

- **B1 深水泊位**：可容船长 320m、图示水深 9.5m；深水船吃水 11.5m、UKC 要求 1m
  ⇒ 需要潮位 `h ≥ 11.5 + 1.0 − 9.5 = 3.0m`。
- 30 分钟一档的模拟潮汐中，**只有 S=20:00 这一个 15 分钟网格起点**能通过全窗口 UKC 校验
  （19:30 档潮位 2.8m 不够；21:30 档回落到 2.75m，20:15/20:30 起点也失败）。
- 该时段唯一有 DEEP 资质且值班的引航员 **PILOT-1 张远航**（值班 17:00–次日 02:00，交接 30m），
  唯一适配 300m/11.5m 船舶的拖轮 **TUG-1 港拖16**（周转 30m）；岸桥 QC-1~QC-4。
- **VOY-A 远集 A**：ETA `19:30–20:30`，作业 60m，需 2 台岸桥。
- **VOY-B 远集 B**：ETA `19:00–20:00`，已提交 20:00 靠泊，与 A 竞争同一组合。
- 另有 VOY-C/D/E 覆盖集装箱、滚装与“ETA 18–19 改 19–20”的开场故事。
- 次日 08:00 前后还有早潮窗口与 PILOT-3/TUG-3 早班，用于“等潮/等资源”的备选与重排。

---

## 2. 核心设计决定：何时占用整组靠泊资源

两种策略都已实现，并可用接口参数或回放对照：

| 策略 | 行为 | 优点 | 代价 |
| --- | --- | --- | --- |
| **`TENTATIVE_HOLD`（默认）** | 调度“暂存”候选时，即在同一事务写入泊位/引航/拖轮/岸桥的 `TENTATIVE` 占用，TTL 默认 120s；到期由 500ms 扫描器自动释放 | 确认后资源不会被抢 | ETA 尚宽时资源可能空等 → 用短 TTL + 一键取消控制 |
| **`ATOMIC_CONFIRM`** | 暂存只保存方案、**不占资源**；确认时在一个事务内重校全部约束并插入 `FIRM` 占用 | 资源周转率高 | 已讨论方案可能在最后一步被别人锁走，确认必须原子重校+锁定 |

暂存接口可用 `"holdResources": false/true` 临时选择策略；`app.planning.hold-on-stage` 改全局默认。

**唯一性保证（不依赖应用锁）**：所有占用统一落在 `resource_occupation` 表。
PostgreSQL 触发器 `trg_occupation_overlap` 在写入前对 `resource_lock` 中该资源的锁父行 `SELECT … FOR UPDATE`，
再检查半开区间重叠；并发确认同一组合时恰好一个事务成功，另一个收到 `23P01/RESOURCE_OVERLAP` → HTTP 409。
另有部分唯一索引保证同一船次至多一个 `CONFIRMED` 方案。

---

## 3. 约束与时间轴模型

设 `S = berth_from`（靠泊操纵开始）：

```
引航段    [S-45, S+15]            占用区间前后各加 handover_min 交接缓冲
拖轮靠泊  [S-15, S+30]            占用尾部加 turnaround_min 周转
靠泊操纵  [S, S+30]               泊位占用开始
装卸作业  [S+30, S+30+duration]   每台岸桥占用至 opsEnd + transfer_min 转场
拖轮离泊  [opsEnd, opsEnd+30]     同样加周转缓冲
离泊段    [opsEnd, opsEnd+30]     泊位占用结束 = opsEnd+30
```

- **UKC**：对 `[S, opsEnd]` 与每个重叠潮汐档校验 `chart_depth + tide_height − draft ≥ ukc_min(=1.0)`，并检查潮汐覆盖完整性。
- 泊位：船长 ≤ `max_length_m`，货类必须在 `berth_cargo_limit` 名单内。
- 引航员：船长/吃水/资质（深水大船需 `DEEP`）+ 占用区间（含交接）完整落在某个 `work_window`。
- 拖轮：船长/吃水适配 + 靠/离两区间（含周转）均不冲突、在班。
- 岸桥：需要 N 台不同岸桥，每台工作窗覆盖 `[opsStart, opsEnd+transfer]`，且无占用/故障重叠。
- 候选生成：15 分钟网格，从 `etaStart` 枚举到 `etaEnd + 960m`；ETA 窗口内不可行点全部保留并给出
  结构化原因（`UKC_VIOLATION`、`*_HELD_TENTATIVE`、`*_BOOKED_FIRM`、`PILOT_OFF_DUTY`、
  `INSUFFICIENT_CRANES` 等，含占用方船次/方案/暂留到期时间），可行点按时间先后取前 6。

---

## 4. 目录结构

```
pom.xml / Dockerfile / docker-compose.yml
scripts/replay.sh                 # 命令行一键验证（登录 + /api/replays/full + 断言）
src/main/resources/
  application.yml                 # DB 环境变量、TTL、候选网格配置
  schema.sql                      # 表/索引（幂等）；触发器由 Java 下发
  static/                         # 单页前端 index.html / app.js / styles.css
src/main/java/com/port/berth/
  PortBerthApplication.java
  config/                         # 鉴权拦截器、CORS、OpenAPI、PG 触发器初始化
  domain/                         # 枚举、Catalog 记录、Eval、ConstraintMath
  hazmat/                         # 【特性2】危货堆区模型 + HazmatEngine 纯函数判定（容量/隔离/凭证/道路）
  repo/                           # CatalogDao / PlanDao / HazmatDao（JdbcTemplate + 行映射）
  service/                        # PlanningEngine / PlanService / HoldSweeper
                                  # ExecutionService / DisruptionService / ReplanService
                                  # ReplayService / HazmatService / HazmatReplayService
                                  # SeedService / AuthService
  web/                            # REST 控制器（含 HazmatController）、全局异常处理
src/test/java/.../                # 11 个纯单元测试：约束数学 + 样例唯一窗口 + 7 个危货堆区规格
```

---

## 5. 一键启动

### 5.1 Docker Compose（推荐，干净环境）

```bash
cd /workspace/92-feature-2
docker compose up --build
```

- 应用：<http://localhost:8080>（单页操作界面）
- Swagger UI：<http://localhost:8080/swagger-ui.html>，OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- PostgreSQL 18：容器内 `db:5432`，宿主机映射 **5433**（避免与本机 5432 冲突），
  库/用户/密码 `berth/berth/berth123`，数据卷 `pgdata`
- 镜像构建内已配置 `docker/settings-docker.xml` 走阿里云 Maven 源

> 本任务容器内没有安装 Docker CLI/守护进程，因此镜像构建与 `docker compose up` 未在本会话实际执行；
> `docker-compose.yml` 已通过 YAML 解析校验，Dockerfile 为标准多阶段构建。应用本身已用等价方式
> （JDK 25 + 可执行 jar + 真实 PostgreSQL 18）完整验证，见第 7 节。

### 5.2 本地开发（使用任务容器预装运行时）

PostgreSQL 18 已在 `127.0.0.1:5432` 运行，开发库/账号 `ccdev/ccdev`：

```bash
# 连通性检查
/opt/postgresql-18/bin/psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" -c 'select 1'

# 若数据库进程未运行，可尝试：
# /opt/postgresql-18/bin/pg_ctl -D /tmp/cc-services/postgres -l /tmp/pg.log start

cd /workspace/92-feature-2
export JAVA_HOME=/opt/jdk-25
mvn spring-boot:run
# 或：mvn package && /opt/jdk-25/bin/java -jar target/berth-coordination.jar
```

零额外配置即可启动：默认连接 `jdbc:postgresql://127.0.0.1:5432/ccdev`（`ccdev/ccdev`），
首次启动自动执行 `schema.sql`、创建两个 PL/pgSQL 触发器并播种样例数据（幂等）。
也可用环境变量覆盖：`DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/PORT/HOLD_ON_STAGE/HOLD_TTL_SECONDS`。

---

## 6. 测试账号

| 用户名 | 密码 | 角色 | 能做什么 |
| --- | --- | --- | --- |
| `agent` | `agent123` | AGENT 船代 | 提交船次、补报 ETA、撤回授权 |
| `dispatch` | `dispatch123` | DISPATCHER 调度 | 生成候选、暂存/确认/取消、扰动录入、改靠/重排、回放、重置 |
| `pilot` | `pilot123` | TEAM 引航班组 | 看板、开工/完成/登记作业 |
| `tug` | `tug123` | TEAM 拖轮班组 | 同上 |
| `crane` | `crane123` | TEAM 岸桥班组 | 同上（含部分装卸量登记） |
| `admin` | `admin123` | ADMIN | 全部权限 |

登录：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatch","password":"dispatch123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
```

除 `POST /api/auth/login` 与 `GET /api/meta` 外，`/api/**` 都需要
`Authorization: Bearer <token>`；角色不足返回 403。Swagger 中点 **Authorize** 填 token。

---

## 7. 本题验证方式与实际验证结论

### 7.1 自动化验证（本会话实际执行）

```bash
mvn test
# Tests run: 11, Failures: 0, Errors: 0, Skipped: 0  → BUILD SUCCESS
#   其中 HazmatEngineTest 7 个，锁定 10:20 vs 11:00、40 分钟差、30m 隔离、
#   凭证迟到/缺失、消防通道阻断、缺离场证明、容量超限等规格

mvn package
# 生成 target/berth-coordination.jar（约 28 MB，Spring Boot fat jar）
```

```bash
# 启动 jar 后执行（默认 http://127.0.0.1:8080）
bash scripts/replay.sh
# ==> 断言：75/75 通过；allPassed=True
# ALL_REPLAY_CHECKS_PASSED
```

> 75 = 原有靠泊/扰动 46 条 + 危货堆区新增 29 条（对照 12、凭证闸门 9、道路 3、接货 5）。
> 危货回放每块只重置 `hz_*` 子域，**不会清空登录 token**；`POST /api/replays/hazmat` 可单独运行。

该脚本真实登录、调用 `POST /api/replays/full`（约 12 秒，含真实 2s TTL 等待、
500ms 扫描释放与双线程并发确认竞速），递归统计全部断言并以退出码反映结果。

此外实际执行并通过的验证：

- **全新空库验证**：另建空数据库、以 `DB_NAME=... PORT=8081 java -jar` 启动，
  建表/触发器/播种一次成功，回放同样全部通过（验证了 Compose 首次启动的初始化路径）。
- **HTTP 权限/业务冒烟 8/8**：匿名 401、公开 meta、船代提交船次、船代暂存 403、
  VOY-E ETA 补报状态变 `ETA_UPDATED`、TEAM 看板 200、错误密码 400、OpenAPI 200。
- 前端 `node --check app.js` 通过；所有按钮处理函数均有定义；`/`、`/app.js`、`/styles.css`、
  `/swagger-ui/index.html` 均实际返回 200。
- 回放中包含**数据库层自证不可变**：对 `work_record` 直接 `UPDATE/DELETE` 被触发器拒绝。
- **危货堆区 HTTP 端到端（本次实际执行）**：匿名 `GET /api/hazmat/comparison` 返回 401；
  `agent` 调 `PUT /api/hazmat/yards/Y1/clearance` 返回 403；dispatch 下基线 `Y1@10:20` 为
  `available=true/AVAILABLE`；把凭证改登为实际 `10:40` 生效后，`10:20` 与 `10:39` 均为
  `available=false/CERT_LATE` 且 `certificateLate=true`，`10:40` 才恢复可用；此时
  `POST /api/hazmat/receiving`（Y1/共享/10:20）返回 409 `HAZMAT_REJECTED` 且 details 含
  `CLEARANCE_CERT_LATE`；恢复准时凭证后同请求 `accepted=true` 并写入 `hz_occupation`。
- **危货全新空库复验（本次实际执行）**：另建空库 `ccdev_hzfresh`、`PORT=8081` 启动 fat jar，
  `hz_*` 七张表与播种一次成功，`POST /api/replays/hazmat` 在该空库 `allPassed=true`；验证后已停实例并删除该临时库。
- **前端危货面板（本次实际执行）**：`node --check app.js` 通过；并用 Node `vm` + DOM 桩拿真实
  `/api/hazmat/comparison`、`/yards`（准时与迟到两份）响应实际执行 `renderHzCompare/renderHzTimeline/renderHzYards`，
  12 项渲染断言全过（含 `10:20/11:00`、`ZONE_DEDICATED/ISOLATION_*`、`12/20、15/15`、迟到态“凭证迟到未生效”）；
  `index.html` 内所有 `onclick` 处理函数与 `hz-*` 元素 ID 静态一致性检查通过。本环境无无头浏览器，未做真实像素/点击验证。

### 7.2 界面手工验证路径

1. 打开 <http://localhost:8080>，用 `dispatch/dispatch123` 登录（登录页可点账号芯片填充）。
2. **② 调度工作台** → 选 `VOY-A` → “生成/刷新候选计划”：可见唯一绿色可行卡
   `B1 20:00`，分配 `PILOT-1/TUG-1/QC-1/QC-2`；其余红色卡给出 UKC/值班等原因。
   - 设 TTL=2 秒点“暂存并暂留资源”，卡片变 `STAGED` 并显示到期时间；不确认，数秒后刷新可见 `EXPIRED`。
   - 对 `VOY-B` 生成候选，可见 20:00 卡被 A 的暂留/确认阻挡及结构化等待原因。
   - 可试“仅暂存不占资源”，再让 B 先确认，最后 A “直接原子确认”会收到 409。
3. **③ 班组看板**：只有 `CONFIRMED` 方案出现；可开工、登记部分作业量、完成分段。
4. **④ 模拟与扰动**：修订 21:00 潮位为 `2.20`、插入 QC-2 故障窗口、查看影响项；
   回到调度页对确认方案“扰动后重排”，或用确认卡上的“改靠/改时”。
5. **⑤ 危货堆区接货**：顶部两张对照卡直接给出 固定专用区 `乙 11:00→Z` 与 清场共享 `乙 10:20→Y1（提早40分钟）`，
   每块卡列出 Y1/Y2/Z 的原因码与中文理由；中部是 09:00/10:00/10:20/11:00 占用量表，下部是三块堆位看板与
   “离场/清场凭证/消防通道/隔离”闸门清单。把观察时刻设为 `10:20`：
   - 默认准时凭证时 Y1 绿色 `AVAILABLE/可接货`；点 **⏰ 模拟凭证迟到（实际10:40）** 后不改动观察时刻，
     Y1 立即变琥珀色 `清场凭证迟到未生效·不可用 / CERT_LATE`，证明“越过计划 10:20 也不显示可用”；
     把时刻调到 `10:40` 才转绿。
   - 点 **阻断消防通道**，Y1 变红 `ROAD_BLOCKED`；用底部“尝试接乙货”可看到 409 与结构化理由。
   - “尝试接乙货”选 `FIXED_DEDICATED + Y1@10:20` 必被 `ZONE_DEDICATED` 拒；选共享 Y1@10:20 成功落库。
6. **⑥ 一键回放验证**：顺序集 / 扰动集 / **危货堆区** / 完整集按钮分别运行对应回放，页面渲染每条断言与完整 JSON。
7. **① 船代申报**：可新建船次、对 VOY-E 把 ETA 18–19 补报为 19–20、撤回授权。

---

## 8. 一键回放到底核对了什么

`POST /api/replays/orderings`、`/api/replays/disruptions`、`/api/replays/full`
（DISPATCHER/ADMIN）每次先重置固定样例，再输出带断言与完整时间线的报告：

| 回放块 | 核对点（全部 PASS） |
| --- | --- |
| **顺序1：A 暂存后未确认** | A 的 B1 20:00 候选可行且选到 P1/T1/QC1/QC2；A `STAGED`；B 同时点不可行且原因含 `*_HELD_TENTATIVE`；B 强确认 409；TTL 到期 A `EXPIRED`、≥5 条占用全 `RELEASED`、扫描延迟 <3s；A 确认数 0；释放后 B 可行并成为唯一 `CONFIRMED` |
| **顺序2：A 暂留期内确认** | A `CONFIRMED` 且唯一；B 被 `*_BOOKED_FIRM` 阻挡；B 暂存/确认均 409；B 得到等待 >120m 的次日备选；B 确认数 0 |
| **顺序3：B 先确认** | B 直接原子确认成功；A 候选显示 FIRM 阻挡；A 暂存、最后一步确认均 409；确认计数 B=1/A=0 |
| **并发竞速** | 两线程同时确认 A/B 同一组合：恰好 1 个成功，库中 A/B 合计仅 1 个 `CONFIRMED`（触发器+资源锁行兜底） |
| **策略对照** | `holdResources=false` 时暂存零占用；B 先确认；A 事后确认 409（“最后一步失效”），并附两种策略权衡说明 |
| **潮汐修订** | 先写 1 条引航完成记录 + 1 条 15 分钟装卸台账；21:00 潮位 3.2→2.2 后分析出指向装卸段的 `UKC_VIOLATION`；重排模式 `RESCHEDULE`、次日 08:00 新方案 `CONFIRMED`、只排剩余 45m；2 条记录保留；DB 阻止 UPDATE/DELETE |
| **岸桥故障** | QC-2 20:45–22:30 故障生成 `CRANE_FAULT`；重排为 `SAME_SLOT_REASSIGN`，新分配含 QC-3 不含 QC-2；引航记录保留 |
| **改靠指令** | 对 VOY-D 下达改靠 B2↔B3：新版本确认且泊位变化；≥4 条 `BERTH_CHANGE` 逐段影响说明 |
| **ETA 迟到** | ETA 最早时间晚于计划靠泊点时生成 ≥4 条 `ETA_LATE`（引航/拖轮/靠泊/装卸），随后重排出 `CONFIRMED v2` |
| **危货堆区对照（本特性，29 条）** | 固定专用→乙 `Z 11:00`（等 120m），Y1 `ZONE_DEDICATED`、Y2 `ISOLATION_DISTANCE_INSUFFICIENT 30<50`、Z `YARD_OCCUPIED_UNTIL`；共享 Y1→`10:20`（等 80m）、提早 40m；09:00 占用 Y1=12/20、Z=15/15；凭证改晚到 10:40 后 10:20/10:39 仍 `available=false/CERT_LATE`、10:40 才可用、对照最早时间顺延 10:40；凭证缺失时 11:00 仍 `CERT_MISSING`；消防通道阻断→`ROAD_BLOCKED/FIRE_LANE_BLOCKED` 且接货 409、恢复后可用；固定 Y1/10:20、Z/10:20、共享 Y1/10:19 强接分别 409 且原因码正确，Y1/10:20 成功并写 `hz_occupation` |

---

## 9. 主要 HTTP API

| 方法/路径 | 角色 | 说明 |
| --- | --- | --- |
| `POST /api/auth/login` | 公开 | 登录拿 token |
| `GET /api/meta` | 公开 | 船舶/泊位/潮汐/人员/设备/故障/演示账号 |
| `GET/POST /api/voyages` | 登录 / AGENT+ | 船次列表/提交 |
| `GET /api/voyages/{id}` | 登录 | 船次 + 全部方案详情 + 记录 + 事件 |
| `PATCH /api/voyages/{id}/eta` | AGENT+ | 补报 ETA；若有确认方案同时做 ETA 迟到影响分析 |
| `POST /api/voyages/{id}/withdraw` | AGENT+ | 撤回授权并释放该船全部资源 |
| `POST /api/voyages/{id}/candidates/generate` | DISPATCHER+ | 枚举候选并落库 |
| `GET /api/plans`、`GET /api/plans/{id}` | 登录 | 方案/分段/原因/分配 |
| `POST /api/plans/{id}/stage` | DISPATCHER+ | body：`{"holdResources":true,"holdTtlSeconds":120}` |
| `POST /api/plans/{id}/confirm` | DISPATCHER+ | 事务内重校 + 原子锁定 FIRM |
| `POST /api/plans/{id}/cancel` | DISPATCHER+ | 取消暂存并释放 |
| `POST /api/segments/{id}/start|complete|log` | TEAM/DISPATCHER | 开工/完成/部分作业台账 |
| `GET /api/teams/board` | 登录 | 仅 CONFIRMED 方案的引航/拖轮/岸桥/泊位看板 |
| `PUT /api/simulation/tides/{slotId}` | DISPATCHER+ | 修订潮汐并分析 |
| `POST /api/simulation/crane-faults` | DISPATCHER+ | 插入设备故障窗口并分析 |
| `POST /api/plans/{id}/replan` | DISPATCHER+ | 扰动重排（默认自动确认） |
| `POST /api/plans/{id}/change-berth` | DISPATCHER+ | 改靠/改时指令 |
| `GET /api/disruptions[/{id}]` | 登录 | 扰动与影响项 |
| `GET /api/hazmat/scenario` | 登录 | 危货堆区固定事实：Y1/Y2/Z、距离矩阵、隔离规则、货物、占用、凭证、道路 |
| `GET /api/hazmat/comparison`（别名 `/compare`）`?at=` | 登录 | **两种安排对照**：可接货时间/等待分钟/占用时间轴/每堆位原因码与中文理由 |
| `GET /api/hazmat/yards`、`GET /api/hazmat/yards/{code}` `?at=` | 登录 | 堆位（看板/单个）可用性：`available/status/gates/reasons` 与凭证生效/迟到标记；`at` 缺省 `10:20` |
| `PUT /api/hazmat/yards/{code}/clearance` | DISPATCHER+ | 登记清场凭证：`{certNo,plannedReadyAt,certifiedAt,reviewPassed}`；`certifiedAt` 晚于计划即“迟到” |
| `POST /api/hazmat/scenario/late-certificate` / `.../on-time-certificate` | DISPATCHER+ | 一键把 Y1 凭证设为“实际 10:40 迟到” / 恢复“10:20 准时” |
| `POST /api/hazmat/roads/{code}` | DISPATCHER+ | 设置消防通道/转运道路 `{clear,reason,restoreAt}` |
| `POST /api/hazmat/yards/{code}/departure` | DISPATCHER+ | 登记前批货实际离场证明 `{departedAt}` |
| `POST /api/hazmat/receiving` | DISPATCHER+ | 尝试接货 `{cargoCode,strategy,yardCode,at}`；不满足返回 409 `HAZMAT_REJECTED`+理由数组 |
| `POST /api/hazmat/reset` | DISPATCHER+ | 仅把 `hz_*` 子域恢复固定样例（不动靠泊数据/登录态） |
| `POST /api/replays/orderings|disruptions|hazmat|full` | DISPATCHER+ | 一键回放（`full` 现含危货块，共 75 条断言） |
| `POST /api/admin/reset` | DISPATCHER+ | 清空并恢复固定样例（含危货子域；会重建账号并使旧 token 失效，重新登录即可） |

---

## 10. 配置与运维说明

- `app.planning.hold-on-stage`、`app.planning.default-hold-ttl-seconds`、
  `app.planning.candidate-step-minutes`、`app.planning.max-wait-minutes` 见 `application.yml`，
  均可用同名环境变量覆盖（`HOLD_ON_STAGE`、`HOLD_TTL_SECONDS` …）。
- `HoldSweeper` 每 500ms 扫描 `STAGED` 且到期的方案，释放其 `TENTATIVE` 占用并写 `HOLD_EXPIRED` 事件。
- `ccdev/ccdev` 是任务容器的隔离开发账号，**不要直接作为生产配置**；Compose 使用独立的 `berth` 库与密码。

---

## 11. 危货临时堆区接货对照（特性 92-feature-2）

### 11.1 固定事实（播种于 `hz_*` 表，`HazmatDao`）

| 要素 | 数据 |
| --- | --- |
| 堆位 / 容量 | **Y1=20t、Y2=15t、Z=15t** |
| 距离 | Y1↔Y2=**30m**；Y1↔Z=55m、Y2↔Z=58m（**Z 距两者均 >50m**） |
| 隔离规则 | 甲类 ↔ 乙类 ≥ **50m** |
| 到港货物 | 甲 `JIA` **12t**、乙 `YI` **10t**，同为 2026-11-26 **09:00** 到港；转运车已获批 |
| 甲的动线 | 09:00 进 Y1 → **10:00** 实际离场送场外合规区（有 `departed_at` 证明） |
| Y1 清场 | 计划复核完成 **10:20**；凭证 `CL-Y1-1020` 准时 10:20 生效 |
| Z | 既有作业（检修/倒垛，占 15t）**08:00–11:00** |
| 道路 | `FIRE-Y1` 消防通道/转运道路，基线巡查畅通 |

### 11.2 两种安排的结论（`GET /api/hazmat/comparison`）

| 安排 | 乙最早可接货 | 堆位 / 接入占用 | 等待（自 09:00） | 各堆位被拒理由（在到港时刻评估） |
| --- | --- | --- | --- | --- |
| **固定专用区** `FIXED_DEDICATED` | **11:00** | **Z**，10/15t（67%） | 120 分钟 | **Y1** `ZONE_DEDICATED`（甲类固定专用，不跨类）＋当时 `YARD_OCCUPIED_UNTIL 10:00`；**Y2** `YARD_NOT_DESIGNATED` ＋ `ISOLATION_DISTANCE_INSUFFICIENT`（距 Y1 甲货 30m<50m）；**Z** `YARD_OCCUPIED_UNTIL 11:00` |
| **清场分时共享 Y1** `TIME_SHARED_CLEARANCE` | **10:20** | **Y1**，10/20t（50%） | 80 分钟 | Y1 依次过 `YARD_OCCUPIED_UNTIL 10:00`→`DEPARTURE`→`CLEARANCE_CERT`（10:20）→`FIRE_LANE`；**Y2** 仍 `YARD_NOT_DESIGNATED`＋`ISOLATION_*`；**Z** 仍占用至 11:00 |

**比较结论：共享 Y1 比固定专用死等 Z 提早 40 分钟接乙货。**

关键时刻占用量（`occupancyTimeline`）：

| 时刻 | Y1 | Y2 | Z |
| --- | --- | --- | --- |
| 09:00 到货 | **12/20t（甲）** | 0/15t | **15/15t（既有作业）** |
| 10:00 甲离场 | 0/20t（物理清空，待凭证） | 0 | 15/15t |
| 10:20 计划清场完成 | 凭证准时→可接乙 10/20t | 0 | 15/15t |
| 11:00 Z 腾空 | 乙 10/20t（共享安排） | 0 | 固定安排接乙 10/15t |

### 11.3 清场凭证迟到不变量（强制）

`HazmatEngine` 只认真实凭证的实际生效时间 `clearance.certifiedAt`，**计划时间 `plannedReadyAt` 不参与可用性判定**：

- 凭证准时（`certifiedAt=10:20`）：`at=10:20` 起 Y1 `available=true / AVAILABLE`。
- 凭证迟到（如 `certifiedAt=10:40`）：`10:20` 与 `10:39` 一律 `available=false / CERT_LATE`
  （原因码 `CLEARANCE_CERT_LATE`，并带 `certificateLate=true`），**10:40 才转可用**，
  对照中的共享最早时间也顺延为 10:40（仍早于 11:00）。
- 凭证缺失或复核未过：即使查询时刻已到 11:00，Y1 仍 `available=false`，状态 `CERT_MISSING/CLEARANCE_FAILED`，
  且 `earliestAcceptAt=null`（不会拿计划时间瞎承诺）。
- 消防通道不畅通：即便凭证已生效，Y1 仍 `ROAD_BLOCKED / FIRE_LANE_BLOCKED`，接货返回 409。

命令行亲历“迟到不变量”：

```bash
T=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatch","password":"dispatch123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 把 Y1 凭证改登为 10:40 才实际生效
curl -s -X PUT http://127.0.0.1:8080/api/hazmat/yards/Y1/clearance \
  -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"certNo":"CL-LATE","plannedReadyAt":"2026-11-26T10:20:00",
       "certifiedAt":"2026-11-26T10:40:00","reviewPassed":true}'

# 越过计划 10:20 也必须是 available=false / CERT_LATE
curl -s "http://127.0.0.1:8080/api/hazmat/yards?at=2026-11-26T10:20" \
  -H "Authorization: Bearer $T"
```

### 11.4 代码位置与可测性

- 纯领域模型/引擎（无 Spring、无 DB）：`hazmat/Hazmat.java`、`hazmat/HazmatEngine.java`；
  规格单测 `src/test/java/com/port/berth/HazmatEngineTest.java`（7 例）。
- JDBC/播种：`repo/HazmatDao.java`；业务/对照/接货闸门：`service/HazmatService.java`；
  回放断言：`service/HazmatReplayService.java`；REST：`web/HazmatController.java`。
- 表结构：`schema.sql` 末尾 `hz_yard / hz_yard_distance / hz_cargo / hz_isolation_rule /
  hz_occupation / hz_clearance / hz_road_state`，全部 `CREATE TABLE IF NOT EXISTS`，
  老库重启会按需补播，管理端重置与 `/api/hazmat/reset` 都会恢复本固定样例。
- 前端：第 **⑤ 危货堆区接货** 页签（`index.html#tab-hazmat` + `app.js` 的 `hz*` 函数）。
- 服务不依赖 Redis/港口实机/第三方账号；外部潮汐与设备状态全部来自库内模拟表和模拟录入接口。
