# 药店冷藏药品配送签收与温控赔付服务

基于 **Kotlin + Spring Boot 3.3 + Redis** 的冷藏药品（胰岛素、生物制剂、眼药水、特殊冷藏药品）配送签收与温控赔付服务。

覆盖能力：

- **下单与审核**：处方照片归档 → 处方审核 → 药师复核 → 出库（批号、有效期、保温箱编号、冰袋数量、出库温度、预计送达时间）
- **配送监控**：骑手取药后持续上报箱内温度、路线、开箱次数、配送状态；超温 / 开盖异常自动产生异常事件
- **患者签收**：本人/代收人身份确认、药品外观、温度标签、用药提醒确认
- **患者不在家处理**：骑手到达无人签收时登记**服务记录**（等待时间、电话结果、是否可放置冷藏代收点、实测箱温）与**药师意见**；超过**保温箱安全时限**后订单不能直接二次派送，须患者改约并由药师重新评估温控（改约时**重新计算保温箱可用时长**）；药师**确认退回箱温**前骑手不能把药品带回继续派送；改约失败自动**生成退回药店任务**，药品到店后重新判断、赔付结案
- **异常协同**：处方退回、骑手绕路超温、患者不在家、开盖异常、包装破损、患者退药、药师判不可用——异常事件把药师、仓库、骑手、患者、客服、医保结算、赔付人员连接到同一订单
- **最终处理**：药品报损 / 退回库存、补发（生成 0 元新订单）、退款（医保/自费自动拆分）、骑手责任与扣款、患者用药风险提醒
- **配套能力**：处方照片归档、冷链箱循环使用、骑手培训（未培训不能取药）、医保自费拆分、患者用药回访
- **核心规则**：冷藏药品一旦出库，任何签收失败、超温或退药都会把药品可用性置为 `PENDING_REJUDGMENT`，必须药师重新判断后才能继续配送或结案
- **双视角**：药店端按订单查看药品是否仍可使用；患者端查看超温、补发、退款、用药提醒的当前状态

## 本地启动

前置条件：JDK 17+、Maven 3.6+、Redis（默认 `localhost:6379`，可用环境变量覆盖）。

### 方式一：Docker 一键启动（含 Redis）

```bash
docker compose up --build
# 服务监听 http://localhost:8080，Redis 监听 localhost:6379
```

### 方式二：本地直接运行

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

## 接口文档（OpenAPI 3.x / Swagger UI）

服务启动后可直接访问（本项目为纯后端服务）：

- Swagger UI：<http://localhost:8080/swagger-ui.html>（自动跳转到 `/swagger-ui/index.html`）
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

在 Swagger UI 中点击 **Authorize** 或直接为请求添加 `X-User-Id` / `X-Role` 请求头即可调试（接口本身无 cookie 鉴权，使用请求头）。

## 测试账号（请求头鉴权）

所有接口通过请求头声明身份，未携带返回 401，角色不符返回 403：

| 请求头 | 说明 |
|---|---|
| `X-User-Id` | 用户标识，如 `pharmacist-1` |
| `X-Role` | 角色，见下表 |

| 角色 | 测试账号示例 | 职责 |
|---|---|---|
| `PHARMACIST` | `pharmacist-1` | 处方审核、药师复核、药师重新判断、不在家药师意见、改约温控重评、退回箱温确认 |
| `WAREHOUSE` | `warehouse-1` | 出库、保温箱回收、退回药品到店登记 |
| `RIDER` | `rider-1` | 取药、遥测上报、配送尝试、不在家登记/放置代收点/退回药店 |
| `PATIENT` | `patient-1` | 签收、退药、改约、查看本人订单 |
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
DELIVERING --患者不在家--> SIGN_FAILED（服务记录：等待时间/电话结果/冷藏代收点/箱温 → 药师意见）
SIGN_FAILED --未超安全时限，药师判可用/意见继续配送/同意代收点--> DELIVERING
SIGN_FAILED --患者改约--> 药师温控重评（重新计算保温箱可用时长）--通过--> DELIVERING
SIGN_FAILED --超过安全时限--> 禁止直接二次派送（仅可改约重评或退回）
SIGN_FAILED --药师要求退回 / 改约失败--> 退回药店任务(CREATED)
        --药师确认退回箱温--> RETURN_CONFIRMED --骑手带回--> RETURNING --到店--> AWAITING_RESOLUTION
SIGNED/DELIVERING --患者退药--> RETURN_REQUESTED
（以上异常均使 usability = PENDING_REJUDGMENT，必须药师重新判断）
药师判不可用 --> AWAITING_RESOLUTION --赔付处理--> CLOSED / REISSUED（生成补发新订单）
```

### 患者不在家处理规则（重点）

1. 骑手到达无人签收，调用到达登记建立**服务记录**：累计等待时间、电话结果（`ANSWERED_AGREED_WAIT` / `ANSWERED_REQUEST_RESCHEDULE` / `ANSWERED_REFUSED` / `NO_ANSWER` / `WRONG_PERSON`）、是否有冷藏代收点、实测箱温；等待过程中可持续补充。订单进入 `SIGN_FAILED`，`usability=PENDING_REJUDGMENT`，并产生 `PATIENT_NOT_HOME` 异常事件。
2. 药师结合服务记录给出**意见**：继续配送 / 放置冷藏代收点 / 改约 / 退回药店。
3. 出库时记录**保温箱安全配送时限**（`safetyLimitMinutes`，默认 120 分钟，出库起算）。超过安全时限：
   - 自动产生 `SAFETY_LIMIT_EXCEEDED` 事件；
   - 药师不能直接判可用恢复二次派送（`/rejudgment` 返回 409），"继续配送"意见同样被拦截；
   - 必须走**患者改约 + 药师温控重评**（核对超温记录与保温箱剩余可用时长能否覆盖新预约时间），或**退回药店**。
4. 患者同意改约时，服务**立即按剩余时长重新计算保温箱可用截止时间**（`boxValidUntil` / `remainingBoxMinutes`）；药师重评通过后订单按新预约时间恢复 `DELIVERING`。
5. 药师**确认退回箱温**前，骑手端调用"带回"会被拒绝（也不能继续派送）；确认后骑手带回，订单进入 `RETURNING`；到店后进入 `AWAITING_RESOLUTION`，药师重新判断、赔付结案，保温箱回收循环。
6. 改约失败（患者无法签收/联系不上等）生成**退回药店任务**并产生 `RESCHEDULE_FAILED` 事件。

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
| POST | `/api/orders/{id}/outbound` | WAREHOUSE | 出库（批号/有效期/保温箱/冰袋/出库温度/预计送达；出库温度须在 2-8°C；`safetyLimitMinutes` 可选，默认 120） |
| POST | `/api/orders/{id}/rejudgment` | PHARMACIST | **药师重新判断药品是否可用** |
| GET | `/api/orders/{id}/resolution/suggestion` | COMPENSATION 等 | 赔付建议（按异常自动推导） |
| POST | `/api/orders/{id}/resolution` | COMPENSATION | 赔付处理：报损/补发/退款/骑手责任/风险提醒 |
| POST | `/api/orders/{id}/follow-ups` | CUSTOMER_SERVICE, PHARMACIST | 用药回访（签收后） |

### 骑手侧
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders/{id}/pickup` | 取药（须已通过冷链培训） |
| POST | `/api/orders/{id}/telemetry` | 上报温度/路线/开箱/配送状态；超温或开盖次数 >1 自动产生异常事件 |
| POST | `/api/orders/{id}/delivery-attempt` | 配送尝试（`PATIENT_NOT_HOME` → 签收失败并建立服务记录，建议改用下方专用接口） |
| GET | `/api/orders/{id}/telemetry` | 遥测记录 |
| POST | `/api/riders/{riderId}/trainings` | 录入培训（ADMIN/CUSTOMER_SERVICE） |

### 患者不在家处理
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/orders/{id}/not-home/arrival` | RIDER | 到达无人签收：建立服务记录（等待秒数、电话结果、代收点、实测箱温） |
| POST | `/api/orders/{id}/not-home/waiting` | RIDER | 等待中补充等待时间/电话结果/代收点/箱温；跨越安全时限自动告警 |
| GET | `/api/orders/{id}/not-home` | 药房各角色/本单骑手 | 完整处理视图：服务记录、改约、退回任务、剩余保温时长、能否二次派送 |
| POST | `/api/orders/{id}/not-home/opinion` | PHARMACIST | 药师意见：`CONTINUE_DELIVERY` / `LEAVE_AT_PICKUP_POINT` / `RESCHEDULE` / `RETURN_TO_PHARMACY` |
| POST | `/api/orders/{id}/not-home/leave-pickup-point` | RIDER | 药师同意后把药品放置冷藏代收点，恢复配送 |
| POST | `/api/orders/{id}/reschedule` | PATIENT, CUSTOMER_SERVICE, RIDER | 患者同意改约签收（新预约时间），立即重算保温箱可用时长 |
| POST | `/api/orders/{id}/reschedule/review` | PHARMACIST | 改约温控重评（核对超温记录、安全时限、剩余时长是否覆盖新预约） |
| POST | `/api/orders/{id}/reschedule/fail` | PATIENT, CUSTOMER_SERVICE, RIDER | 改约失败 → 生成退回药店任务 |
| POST | `/api/orders/{id}/not-home/return/confirm-temp` | PHARMACIST | **药师确认退回箱温**（骑手带回前置条件） |
| POST | `/api/orders/{id}/not-home/return/bring-back` | RIDER | 药师确认后骑手带回（确认前调用返回 409；带回后不得继续派送） |
| POST | `/api/orders/{id}/not-home/return/arrive-pharmacy` | RIDER, WAREHOUSE, CUSTOMER_SERVICE | 药品送回药店，进入待药师重新判断 |
| GET | `/api/return-tasks` | WAREHOUSE, PHARMACIST, CUSTOMER_SERVICE, COMPENSATION, RIDER | 退回药店任务列表（骑手仅看本人） |

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

`ColdChainFlowTests` 共 17 个端到端用例，覆盖：正常签收全流程、处方退回重审、绕路超温赔付（报损+补发+全额退款+骑手全责+风险提醒）、患者不在家恢复配送、开盖异常、包装破损退库、签收后退药、温度标签异常、未培训骑手拦截、角色/归属权限、医保拆分与部分退款、出库温度与保温箱占用校验，以及**患者不在家完整处理：服务记录与药师意见继续配送、患者改约重算保温时长并温控重评、超过安全时限拦截二次派送、改约失败生成退回药店任务、药师确认退回箱温前拦截骑手带回、退回到店报损结案与保温箱回收、冷藏代收点放置**。

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

患者不在家处理流程（在步骤 4 骑手取药后执行）：

```bash
# 1) 骑手到达无人签收：等待时间/电话结果/冷藏代收点/实测箱温
curl -s -X POST localhost:8080/api/orders/$OID/not-home/arrival \
  -H "X-Role: RIDER" -H "X-User-Id: rider-1" -H "Content-Type: application/json" \
  -d '{"waitedSeconds":180,"callResult":"ANSWERED_REQUEST_RESCHEDULE","callNote":"患者要求 2 小时后再送","pickupPointAvailable":true,"pickupPointName":"小区北门药房冷藏柜","boxTempC":5.2}'
# → 订单 SIGN_FAILED，usability=PENDING_REJUDGMENT，产生 PATIENT_NOT_HOME 事件

# 2) 查看处理进展（安全时限、剩余保温时长、改约、退回任务）
curl -s localhost:8080/api/orders/$OID/not-home -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1"

# 3) 患者改约（服务立即重算保温箱可用时长）→ 药师温控重评
NEW_AT=$(( $(date +%s%3N) + 30*60*1000 ))
curl -s -X POST localhost:8080/api/orders/$OID/reschedule \
  -H "X-Role: PATIENT" -H "X-User-Id: patient-1" -H "Content-Type: application/json" \
  -d "{\"newAppointmentAt\":$NEW_AT}"
curl -s -X POST localhost:8080/api/orders/$OID/reschedule/review \
  -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"approved":true,"comment":"剩余保温时长可覆盖新预约时间"}'
# → 订单恢复 DELIVERING，estimatedDeliveryAt 更新为新预约时间；随后患者正常签收

# —— 若超过安全时限：药师直接判可用/继续配送会被 409 拦截；改约失败则走退回 ——
# 4a) 改约失败 → 生成退回药店任务
curl -s -X POST localhost:8080/api/orders/$OID/reschedule/fail \
  -H "X-Role: CUSTOMER_SERVICE" -H "X-User-Id: cs-1" -H "Content-Type: application/json" \
  -d '{"reason":"患者今晚无法返回"}'
# 4b) 药师确认退回箱温（确认前骑手带回返回 409）
curl -s -X POST localhost:8080/api/orders/$OID/not-home/return/confirm-temp \
  -H "X-Role: PHARMACIST" -H "X-User-Id: pharmacist-1" -H "Content-Type: application/json" \
  -d '{"boxTempC":6.2,"comment":"开箱复测 6.2°C，同意带回"}'
# 4c) 骑手带回 → 到店 → 药师重判 → 赔付结案 → 保温箱回收
curl -s -X POST localhost:8080/api/orders/$OID/not-home/return/bring-back -H "X-Role: RIDER" -H "X-User-Id: rider-1"
curl -s -X POST localhost:8080/api/orders/$OID/not-home/return/arrive-pharmacy -H "X-Role: RIDER" -H "X-User-Id: rider-1"
```

## 数据存储（Redis key）

| Key | 内容 |
|---|---|
| `order:{id}` / `idx:status:{s}` / `idx:patient:{pid}` | 订单 JSON 与索引 |
| `telemetry:{orderId}` | 温度/路线遥测 list |
| `incident:{id}` / `idx:incident:order:{orderId}` | 异常事件 |
| `rejudgment:{orderId}` | 药师重判记录 |
| `resolution:{orderId}` | 最终赔付结果 |
| `payment:{orderId}` | 医保/自费拆分与退款进度 |
| `box:{boxNo}` | 保温箱循环记录 |
| `training:{riderId}` / `followup:{orderId}` / `rxphoto:{orderId}` | 培训 / 回访 / 处方照片 |
| `service-visit:{orderId}` / `service-visit-history:{orderId}` | 患者不在家当前/历次服务记录（等待时间/电话结果/代收点/箱温/药师意见） |
| `reschedule:{orderId}` / `reschedule-history:{orderId}` | 患者当前/历次改约记录（重算的保温箱可用时长、温控重评结果） |
| `return-task:{orderId}` / `idx:return-tasks` | 退回药店任务及索引 |

## 项目结构

```
src/main/kotlin/com/pharmacy/coldchain/
├── ColdChainApplication.kt
├── common/          # 鉴权(Auth)、异常、全局错误处理
├── domain/          # 枚举、模型、请求/视图 DTO
├── repo/            # JsonStore(Redis JSON 存储)、OrderRepository
├── service/         # 订单/配送/事件/赔付/支付/保温箱/骑手/回访/患者不在家(NotHomeService)
└── controller/      # 订单、配送、患者、事件、保温箱、骑手、支付、患者不在家(NotHomeController)
src/test/kotlin/...  # ColdChainFlowTests（17 个端到端用例）
```
