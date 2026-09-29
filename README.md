# 药店冷藏药品配送签收与温控赔付服务

基于 **Kotlin + Spring Boot 3.3 + Redis** 的冷藏药品（胰岛素、生物制剂、眼药水、特殊冷藏药品）配送签收与温控赔付服务。

覆盖能力：

- **下单与审核**：处方照片归档 → 处方审核 → 药师复核 → 出库（批号、有效期、保温箱编号、冰袋数量、出库温度、预计送达时间）
- **配送监控**：骑手取药后持续上报箱内温度、路线、开箱次数、配送状态；超温 / 开盖异常自动产生异常事件
- **患者签收**：本人/代收人身份确认、药品外观、温度标签、用药提醒确认
- **患者不在家处理**：骑手到达无人签收后登记**等待时间、电话结果、是否可放置冷藏代收点、药师意见**；安全时限内患者同意改约时**重新计算保温箱可用时长**后二次派送；**超过安全时限订单不能直接二次派送**，须带回药店、药师**确认退回箱温**后重新评估温控；**药师确认前骑手不能自行带回**；改约失败自动**生成退回药店任务**
- **异常协同**：处方退回、骑手绕路超温、患者不在家、开盖异常、包装破损、患者退药、药师判不可用——异常事件把药师、仓库、骑手、患者、客服、医保结算、赔付人员连接到同一订单
- **最终处理**：药品报损 / 退回库存、补发（生成 0 元新订单）、退款（医保/自费自动拆分）、骑手责任与扣款、患者用药风险提醒
- **配套能力**：处方照片归档、冷链箱循环使用、骑手培训（未培训不能取药）、医保自费拆分、患者用药回访
- **核心规则**：冷藏药品一旦出库，任何签收失败、超温或退药都会把药品可用性置为 `PENDING_REJUDGMENT`，必须药师重新判断后才能继续配送或结案；患者不在家时须先有药师处置意见——安全时限内改约并重算保温箱时长，或退回药店确认箱温后重新评估，骑手不能自行决定二次派送或带回
- **双视角**：药店端按订单查看药品是否仍可使用；患者端查看超温、补发、退款、用药提醒的当前状态

## 本地启动

前置条件：JDK 17+、Maven 3.6+、Redis（默认 `localhost:6379`，可用环境变量覆盖）。

```bash
# 1. 启动 Redis（已运行可跳过）
redis-server --daemonize yes

# 2. 启动服务（默认 8080 端口）
mvn spring-boot:run

# 或者打包后运行
mvn package -DskipTests
java -jar target/cold-chain-delivery-1.0.0.jar
```

环境变量：`REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` / `SERVER_PORT`。

### Docker Compose 一键启动（应用 + Redis）

```bash
docker compose up --build
# 服务监听 http://localhost:8080，Redis 监听 localhost:6379
docker compose down            # 停止；加 -v 同时清除数据卷
```

### API 文档（OpenAPI 3.x / Swagger UI）

服务启动后可访问：

- Swagger UI：<http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

在 Swagger UI 中每个接口都可直接填写 `X-User-Id`、`X-Role` 请求头调试。

## 测试账号（请求头鉴权）

所有接口通过请求头声明身份，未携带返回 401，角色不符返回 403：

| 请求头 | 说明 |
|---|---|
| `X-User-Id` | 用户标识，如 `pharmacist-1` |
| `X-Role` | 角色，见下表 |

| 角色 | 测试账号示例 | 职责 |
|---|---|---|
| `PHARMACIST` | `pharmacist-1` | 处方审核、药师复核、药师重新判断、不在家处置意见、确认退回箱温 |
| `WAREHOUSE` | `warehouse-1` | 出库、保温箱回收、退回药品接收 |
| `RIDER` | `rider-1` | 取药、遥测上报、不在家登记/改约/退回交接 |
| `PATIENT` | `patient-1` | 签收、退药、查看本人订单 |
| `CUSTOMER_SERVICE` | `cs-1` | 异常处理、用药回访 |
| `INSURANCE` | `insurance-1` | 医保自费拆分、退款 |
| `COMPENSATION` | `comp-1` | 赔付处理（报损/补发/退款/骑手责任） |
| `ADMIN` | `admin-1` | 骑手培训录入，放行所有接口 |

## 订单状态机

```
PRESCRIPTION_REVIEWING → PRESCRIPTION_REJECTED（退回，可补照片重审）
                     → PHARMACIST_REVIEWING → READY_FOR_OUTBOUND
                     → OUTBOUND → DELIVERING → SIGNED
DELIVERING --超温/开盖异常/包装破损--> DELIVERY_EXCEPTION --药师判可用--> DELIVERING
DELIVERING --骑手到达无人签收--> SIGN_FAILED（等待药师处置意见）
SIGN_FAILED --安全时限内 + 药师同意 + 患者改约（重算保温箱时长）--> RESCHEDULED --骑手二次派送--> DELIVERING → SIGNED
DELIVERING（二次派送仍无人签收）--> 不再改约，直接生成退回药店任务
SIGN_FAILED --超安全时限/超温/药师要求带回/改约失败--> 生成退回药店任务
            --骑手凭授权带回--> RETURNED_TO_PHARMACY
            --药师确认退回箱温并重新评估温控-->
                 ├─ 判可用：READY_FOR_OUTBOUND（释放旧保温箱，重新出库派送）
                 └─ 判不可用：AWAITING_RESOLUTION（报损/补发/退款）
SIGNED/DELIVERING --患者退药--> RETURN_REQUESTED
（出库后异常均使 usability = PENDING_REJUDGMENT，必须药师重新判断）
药师判不可用 --> AWAITING_RESOLUTION --赔付处理--> CLOSED / REISSUED（生成补发新订单）
```

> 关键门禁：`SIGN_FAILED` 状态不能直接调用 `/rejudgment` 恢复配送；必须先走“药师处置意见 → 患者改约重算保温时长”或“退回药店 → 确认箱温 → 温控重估”。

## 患者不在家处理流程

```
骑手到达无人签收 POST /not-home/arrive（等待时间/电话结果/冷藏代收点）
        │  订单 SIGN_FAILED + usability=PENDING_REJUDGMENT，产生 PATIENT_NOT_HOME 事件
        ▼
药师处置意见 POST /not-home/decision
        ├─ WAIT_AND_RESCHEDULE（仅安全时限内、途中无超温才允许）
        │       └─ 患者同意改约 POST /not-home/reschedule（etaMinutes）
        │             ├─ 剩余保温时长足够：重算 boxSafeUntil → RESCHEDULED
        │             │     → 骑手 POST /not-home/resume 二次派送 → 签收（记录关闭）
        │             └─ 剩余时长不足（超安全时限）：改约失败，自动生成退回药店任务（409）
        └─ BRING_BACK（超时限/超温/药师判断温控无把握）：直接生成退回药店任务
                │  药师未授权前，骑手 return/start、return/handover 均 409
                ▼
骑手 POST /not-home/return/start（任务 CREATED→IN_TRANSIT）
骑手 POST /not-home/return/handover（上报交接箱温）→ 订单 RETURNED_TO_PHARMACY
药店 POST /api/return-tasks/{taskId}/receive 确认接收
药师 POST /not-home/return-temp 确认退回箱温（温控重估前提）
药师 POST /rejudgment：可用→重新出库；不可用→赔付流程
```

保温箱安全时限：出库时按 `boxRatedMinutes`（显式指定；未指定时按冰袋数量 ×60 分钟、下限 120 分钟）计算 `boxSafeUntil = outboundAt + 额定保温时长`；改约时以当前时刻为基准，校验剩余时长 ≥ 改约后预计在途时长，并重算安全截止时刻。可用配置 `coldchain.box.min-rated-minutes` 调整默认下限。

## 主要接口

### 药店/药房侧
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/orders` | PHARMACIST, CUSTOMER_SERVICE | 创建订单（含医保金额，自动算自费） |
| GET | `/api/orders?status=&patientId=` | 药房各角色 | 订单列表 |
| GET | `/api/orders/{id}` | 药房各角色 | 药店视角详情（含可用性、遥测、事件、赔付） |
| GET | `/api/orders/{id}/usability` | 药房各角色 | **药品是否仍可使用** |
| POST | `/api/orders/{id}/prescription-photos` | PHARMACIST, CUSTOMER_SERVICE | 处方照片归档（审核前必须至少一张） |
| POST | `/api/orders/{id}/prescription-review` | PHARMACIST | 处方审核（退回产生异常事件） |
| POST | `/api/orders/{id}/pharmacist-review` | PHARMACIST | 药师复核 |
| POST | `/api/orders/{id}/outbound` | WAREHOUSE | 出库（批号/有效期/保温箱/冰袋/出库温度/预计送达/可选额定保温分钟数；出库温度须在 2-8°C） |
| POST | `/api/orders/{id}/rejudgment` | PHARMACIST | **药师重新判断药品是否可用** |
| GET | `/api/orders/{id}/resolution/suggestion` | COMPENSATION 等 | 赔付建议（按异常自动推导） |
| POST | `/api/orders/{id}/resolution` | COMPENSATION | 赔付处理：报损/补发/退款/骑手责任/风险提醒 |
| POST | `/api/orders/{id}/follow-ups` | CUSTOMER_SERVICE, PHARMACIST | 用药回访（签收后） |

### 骑手侧
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders/{id}/pickup` | 取药（须已通过冷链培训） |
| POST | `/api/orders/{id}/telemetry` | 上报温度/路线/开箱/配送状态；超温或开盖次数 >1 自动产生异常事件 |
| GET | `/api/orders/{id}/telemetry` | 遥测记录 |
| POST | `/api/orders/{id}/not-home/arrive` | **骑手到达无人签收：等待时间、电话结果、冷藏代收点** |
| POST | `/api/orders/{id}/not-home/reschedule` | 患者同意改约（骑手/客服登记），**重算保温箱可用时长** |
| POST | `/api/orders/{id}/not-home/resume` | 改约后开始二次派送（校验仍在安全时限且无超温） |
| POST | `/api/orders/{id}/not-home/return/start` | 开始执行退回药店任务（**须药师先要求带回**） |
| POST | `/api/orders/{id}/not-home/return/handover` | 带回药店交接并上报箱温（**药师确认前禁止自行带回**） |
| GET | `/api/orders/{id}/not-home` | 查看不在家服务记录（本单骑手/药房角色） |
| GET | `/api/return-tasks?riderId=` | 退回药店任务列表（骑手只看本人） |
| POST | `/api/return-tasks/{taskId}/receive` | 药店确认接收退回药品（WAREHOUSE/PHARMACIST） |
| POST | `/api/riders/{riderId}/trainings` | 录入培训（ADMIN/CUSTOMER_SERVICE） |

### 药师侧（患者不在家专项）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders/{id}/not-home/decision` | **药师处置意见**：安全时限内等待改约 / 要求带回（超时自动生成退回任务） |
| POST | `/api/orders/{id}/not-home/return-temp` | **药师确认退回箱温**，温控重新评估的前提 |

### 患者侧
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders/{id}/sign` | 签收（身份/外观/温度标签/用药提醒确认；外观或标签异常 → 签收失败并上报） |
| POST | `/api/orders/{id}/return-request` | 申请退药 |
| GET | `/api/patient/orders/{id}` | **患者视角：超温、补发、退款、用药提醒当前状态** |

### 异常事件 / 医保 / 保温箱
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders/{id}/incidents` | 任意角色上报异常 |
| GET | `/api/orders/{id}/incidents` | 订单异常事件列表 |
| POST | `/api/incidents/{id}/resolve` | 客服/赔付关闭事件 |
| GET | `/api/orders/{id}/payment-split` | 医保/自费拆分与退款进度 |
| POST | `/api/orders/{id}/refund` | 退款（按医保占比自动拆分退回渠道） |
| GET | `/api/boxes` | 保温箱列表 |
| POST | `/api/boxes/{boxNo}/recycle` | 回收：完好 → READY 循环再用；破损 → RETIRED |

## 验证方式

### 1. 自动化测试（实际执行过）

```bash
redis-server --daemonize yes   # 测试使用 Redis database 1，不影响默认库
mvn test
```

`ColdChainFlowTests` 共 17 个端到端用例，覆盖：正常签收全流程、处方退回重审、绕路超温赔付（报损+补发+全额退款+骑手全责+风险提醒）、开盖异常、包装破损退库、签收后退药、温度标签异常、未培训骑手拦截、角色/归属权限、医保拆分与部分退款、出库温度与保温箱占用校验，以及患者不在家专项 6 例：

- 安全时限内药师同意等待 → 患者改约重算保温箱时长 → 二次派送签收；
- 药师处置时已超安全时限 → 禁止改约、自动生成退回药店任务；
- 药师确认前骑手私自带回被拦截 → 授权后带回 → 药店接收 → 药师确认退回箱温 → 温控合格重新出库；
- 改约时剩余保温时长不足 → 改约失败生成退回任务 → 退回箱温超标判废并补发赔付；
- 改约后二次派送仍无人签收 → 不再改约，直接生成退回任务；
- 冷藏代收点信息如实记录与服务记录查看权限校验。

### 2. 手工冒烟（curl 全流程）

启动服务后执行（下面脚本可直接复制运行）：

```bash
H_PHARM='-H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1"'
# 1) 创建订单（总额 1000 元，医保 700，自费 300）
curl -s -X POST localhost:8080/api/orders \
  -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"patientId":"patient-1","patientName":"张三","drugType":"INSULIN","drugName":"门冬胰岛素注射液","quantity":2,"prescriptionNo":"RX-2026-0001","totalAmountCents":100000,"insuranceAmountCents":70000}'
# → 记录返回的 id，以下用 $OID 代替

# 2) 处方照片归档 → 处方审核 → 药师复核
curl -s -X POST localhost:8080/api/orders/$OID/prescription-photos -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" -d '{"url":"https://img.example.com/rx1.jpg"}'
curl -s -X POST localhost:8080/api/orders/$OID/prescription-review -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" -d '{"approved":true}'
curl -s -X POST localhost:8080/api/orders/$OID/pharmacist-review -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" -d '{"approved":true}'

# 3) 出库（批号/有效期/保温箱/冰袋/出库温度/预计送达）
curl -s -X POST localhost:8080/api/orders/$OID/outbound -H "X-Role: WAREHOUSE" -H "X-User-Id: warehouse-1" -H "Content-Type: application/json" \
  -d '{"batchNo":"B20260901","expiryDate":"2027-06-30","boxNo":"BOX-001","icePackCount":2,"outboundTempC":5.0,"etaMinutes":45}'

# 4) 骑手培训 → 取药 → 上报温度
curl -s -X POST localhost:8080/api/riders/rider-1/trainings -H "X-Role: ADMIN" -H "X-User-Id: admin-1" -H "Content-Type: application/json" -d '{"course":"COLD_CHAIN_BASICS","score":95,"passed":true}'
curl -s -X POST localhost:8080/api/orders/$OID/pickup -H "X-Role: RIDER" -H "X-User-Id: rider-1"
curl -s -X POST localhost:8080/api/orders/$OID/telemetry -H "X-Role: RIDER" -H "X-User-Id: rider-1" -H "Content-Type: application/json" -d '{"tempC":4.5,"lat":31.23,"lng":121.47,"deliveryStatus":"ON_ROUTE"}'

# 5) 患者签收（本人 + 外观/温度标签/用药提醒确认）
curl -s -X POST localhost:8080/api/orders/$OID/sign -H "X-Role: PATIENT" -H "X-User-Id: patient-1" -H "Content-Type: application/json" \
  -d '{"receiverType":"SELF","receiverName":"张三","idLast4":"1234","appearanceOk":true,"tempLabelOk":true,"reminderAcknowledged":true}'

# 6) 药店看可用性 / 患者看状态
curl -s localhost:8080/api/orders/$OID/usability -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1"
curl -s localhost:8080/api/patient/orders/$OID -H "X-Role: PATIENT" -H "X-User-Id: patient-1"
```

超温赔付流程（在步骤 4 后上报 `{"tempC":12.5,"detour":true}`）：

```bash
curl -s -X POST localhost:8080/api/orders/$OID/telemetry -H "X-Role: RIDER" -H "X-User-Id: rider-1" -H "Content-Type: application/json" -d '{"tempC":12.5,"detour":true}'
# → 订单进入 DELIVERY_EXCEPTION，自动生成 RIDER_DETOUR_OVERTEMP 事件，usability=PENDING_REJUDGMENT
curl -s -X POST localhost:8080/api/orders/$OID/rejudgment -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" -d '{"usable":false,"comment":"长时间超温，效价无法保证"}'
curl -s localhost:8080/api/orders/$OID/resolution/suggestion -H "X-Role: COMPENSATION" -H "X-User-Id: comp-1"
curl -s -X POST localhost:8080/api/orders/$OID/resolution -H "X-Role: COMPENSATION" -H "X-User-Id: comp-1" -H "Content-Type: application/json" \
  -d '{"drugDisposition":"SCRAPPED","reissue":true,"refundAmountCents":100000,"riderLiability":"FULL","riderFineCents":50000,"riskReminder":"本单药品曾因超温报损，请勿使用原配送药品"}'
# → 原订单 REISSUED，补发新订单 READY_FOR_OUTBOUND（0 元），退款按 7:3 拆回医保/自费
```

患者不在家处理（在步骤 4 之后；出库时传 `"boxRatedMinutes":120` 可显式指定保温箱额定时长）：

```bash
# 1) 骑手到达无人签收：等待 5 分钟、电话接通患者同意改约、附近无冷藏代收点
curl -s -X POST localhost:8080/api/orders/$OID/not-home/arrive \
  -H "X-Role: RIDER" -H "X-User-Id: rider-1" -H "Content-Type: application/json" \
  -d '{"waitedMinutes":5,"phoneResult":"ANSWERED_AGREE_RESCHEDULE","phoneNote":"患者约 18 点到家","coolPoint":"NOT_AVAILABLE"}'
# → 订单 SIGN_FAILED，usability=PENDING_REJUDGMENT，记录阶段 AWAITING_DECISION

# 2) 药师处置意见（超过 boxSafeUntil 或途中有超温时，WAIT_AND_RESCHEDULE 会被 409 拒绝并自动生成退回任务）
curl -s -X POST localhost:8080/api/orders/$OID/not-home/decision \
  -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"opinion":"WAIT_AND_RESCHEDULE","comment":"在途时间短，可等待改约"}'

# 3a) 患者同意改约 30 分钟后送达 → 重算保温箱可用时长（剩余不足则 409，自动生成退回药店任务）
curl -s -X POST localhost:8080/api/orders/$OID/not-home/reschedule \
  -H "X-Role: RIDER" -H "X-User-Id: rider-1" -H "Content-Type: application/json" \
  -d '{"etaMinutes":30,"note":"患者 18:00 到家"}'
# → boxRecalc=WITHIN_SAFE_LIMIT，订单 RESCHEDULED，boxSafeUntil 已重算
curl -s -X POST localhost:8080/api/orders/$OID/not-home/resume -H "X-Role: RIDER" -H "X-User-Id: rider-1"
# → DELIVERING，骑手二次派送，随后患者正常 /sign

# 3b) 【与 3a 互斥的另一分支，需另起一单】药师要求带回（超时/改约失败/温控无把握）
curl -s -X POST localhost:8080/api/orders/$OID/not-home/decision \
  -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"opinion":"BRING_BACK","comment":"电话无人接，带回重新评估"}'
# → 生成退回药店任务（记录 returnTaskId）；药师确认前骑手 return/start、return/handover 均返回 409
curl -s -X POST localhost:8080/api/orders/$OID/not-home/return/start -H "X-Role: RIDER" -H "X-User-Id: rider-1"
curl -s -X POST localhost:8080/api/orders/$OID/not-home/return/handover \
  -H "X-Role: RIDER" -H "X-User-Id: rider-1" -H "Content-Type: application/json" \
  -d '{"boxTempC":6.2,"note":"全程未开箱"}'
# → 订单 RETURNED_TO_PHARMACY
curl -s -X POST localhost:8080/api/return-tasks/$RTID/receive \
  -H "X-Role: WAREHOUSE" -H "X-User-Id: warehouse-1" -H "Content-Type: application/json" -d '{"note":"药店签收"}'
# 4) 药师确认退回箱温（重新评估温控前提）→ rejudgment：可用则重新出库，不可用则进入赔付
curl -s -X POST localhost:8080/api/orders/$OID/not-home/return-temp \
  -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"boxTempC":6.0,"comment":"箱温正常，冷链完整"}'
curl -s -X POST localhost:8080/api/orders/$OID/rejudgment \
  -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"usable":true,"comment":"退回箱温 6°C，温控合格，重新出库"}'
# → READY_FOR_OUTBOUND（原保温箱已释放），仓库重新出库派送
```

## 数据存储（Redis key）

| Key | 内容 |
|---|---|
| `order:{id}` / `idx:status:{s}` / `idx:patient:{pid}` | 订单 JSON 与索引 |
| `telemetry:{orderId}` | 温度/路线遥测 list |
| `incident:{id}` / `idx:incident:order:{orderId}` | 异常事件 |
| `rejudgment:{orderId}` | 药师重判记录 |
| `nothome:{orderId}` | 患者不在家服务记录（等待/电话/代收点/药师意见/改约重算/退回箱温） |
| `task:return:{id}` / `idx:tasks:return[/rider:{rid}]` | 退回药店任务及索引 |
| `resolution:{orderId}` | 最终赔付结果 |
| `payment:{orderId}` | 医保/自费拆分与退款进度 |
| `box:{boxNo}` | 保温箱循环记录 |
| `training:{riderId}` / `followup:{orderId}` / `rxphoto:{orderId}` | 培训 / 回访 / 处方照片 |

## 项目结构

```
src/main/kotlin/com/pharmacy/coldchain/
├── ColdChainApplication.kt
├── common/          # 鉴权(Auth)、异常、全局错误处理
├── domain/          # 枚举、模型、请求/视图 DTO
├── repo/            # JsonStore(Redis JSON 存储)、OrderRepository
├── service/         # 订单/配送/事件/赔付/支付/保温箱/骑手/回访/患者不在家(NotHomeService)
└── controller/      # 订单、配送、患者、事件、保温箱、骑手、支付、不在家、退回药店任务
src/test/kotlin/...  # ColdChainFlowTests（17 个端到端用例）
Dockerfile / docker-compose.yml  # 一键启动（应用 + Redis）
```
