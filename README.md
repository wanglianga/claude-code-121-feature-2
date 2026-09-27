# 药店冷藏药品配送签收与温控赔付服务

基于 **Kotlin + Spring Boot 3.3 + Redis** 的冷藏药品（胰岛素、生物制剂、眼药水、特殊冷藏药品）配送签收与温控赔付服务。

覆盖能力：

- **下单与审核**：处方照片归档 → 处方审核 → 药师复核 → 出库（批号、有效期、保温箱编号、冰袋数量、出库温度、预计送达时间）
- **配送监控**：骑手取药后持续上报箱内温度、路线、开箱次数、配送状态；超温 / 开盖异常自动产生异常事件
- **患者签收**：本人/代收人身份确认、药品外观、温度标签、用药提醒确认
- **异常协同**：处方退回、骑手绕路超温、患者不在家、开盖异常、包装破损、患者退药、药师判不可用——异常事件把药师、仓库、骑手、患者、客服、医保结算、赔付人员连接到同一订单
- **最终处理**：药品报损 / 退回库存、补发（生成 0 元新订单）、退款（医保/自费自动拆分）、骑手责任与扣款、患者用药风险提醒
- **配套能力**：处方照片归档、冷链箱循环使用、骑手培训（未培训不能取药）、医保自费拆分、患者用药回访
- **核心规则**：冷藏药品一旦出库，任何签收失败、超温或退药都会把药品可用性置为 `PENDING_REJUDGMENT`，必须药师重新判断后才能继续配送或结案
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

## 测试账号（请求头鉴权）

所有接口通过请求头声明身份，未携带返回 401，角色不符返回 403：

| 请求头 | 说明 |
|---|---|
| `X-User-Id` | 用户标识，如 `pharmacist-1` |
| `X-Role` | 角色，见下表 |

| 角色 | 测试账号示例 | 职责 |
|---|---|---|
| `PHARMACIST` | `pharmacist-1` | 处方审核、药师复核、药师重新判断 |
| `WAREHOUSE` | `warehouse-1` | 出库、保温箱回收 |
| `RIDER` | `rider-1` | 取药、遥测上报、配送尝试 |
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
DELIVERING --患者不在家--> SIGN_FAILED --药师判可用--> DELIVERING
SIGNED/DELIVERING --患者退药--> RETURN_REQUESTED
（以上异常均使 usability = PENDING_REJUDGMENT，必须药师重新判断）
药师判不可用 --> AWAITING_RESOLUTION --赔付处理--> CLOSED / REISSUED（生成补发新订单）
```

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
| POST | `/api/orders/{id}/outbound` | WAREHOUSE | 出库（批号/有效期/保温箱/冰袋/出库温度/预计送达；出库温度须在 2-8°C） |
| POST | `/api/orders/{id}/rejudgment` | PHARMACIST | **药师重新判断药品是否可用** |
| GET | `/api/orders/{id}/resolution/suggestion` | COMPENSATION 等 | 赔付建议（按异常自动推导） |
| POST | `/api/orders/{id}/resolution` | COMPENSATION | 赔付处理：报损/补发/退款/骑手责任/风险提醒 |
| POST | `/api/orders/{id}/follow-ups` | CUSTOMER_SERVICE, PHARMACIST | 用药回访（签收后） |

### 骑手侧
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders/{id}/pickup` | 取药（须已通过冷链培训） |
| POST | `/api/orders/{id}/telemetry` | 上报温度/路线/开箱/配送状态；超温或开盖次数 >1 自动产生异常事件 |
| POST | `/api/orders/{id}/delivery-attempt` | 配送尝试（`PATIENT_NOT_HOME` → 签收失败待药师重判） |
| GET | `/api/orders/{id}/telemetry` | 遥测记录 |
| POST | `/api/riders/{riderId}/trainings` | 录入培训（ADMIN/CUSTOMER_SERVICE） |

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

`ColdChainFlowTests` 共 12 个端到端用例，覆盖：正常签收全流程、处方退回重审、绕路超温赔付（报损+补发+全额退款+骑手全责+风险提醒）、患者不在家恢复配送、开盖异常、包装破损退库、签收后退药、温度标签异常、未培训骑手拦截、角色/归属权限、医保拆分与部分退款、出库温度与保温箱占用校验。

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

## 项目结构

```
src/main/kotlin/com/pharmacy/coldchain/
├── ColdChainApplication.kt
├── common/          # 鉴权(Auth)、异常、全局错误处理
├── domain/          # 枚举、模型、请求/视图 DTO
├── repo/            # JsonStore(Redis JSON 存储)、OrderRepository
├── service/         # 订单/配送/事件/赔付/支付/保温箱/骑手/回访
└── controller/      # 订单、配送、患者、事件、保温箱、骑手、支付
src/test/kotlin/...  # ColdChainFlowTests（12 个端到端用例）
```
