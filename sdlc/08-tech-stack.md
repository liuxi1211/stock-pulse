# 技术选型与基础设施

> **覆盖范围**：C 端后端、B 端 ERP、计算服务三套技术栈选型论证，中间件与基础设施配置，前端技术栈，开发与部署环境，依赖管理与版本控制
> **来源**：由 `ai-stock-analysis-platform-plan.md` 第九章扩展优化而成
> **关联文档**：02 系统架构、06 AI 对话中枢、07 数据架构

---

## 一、技术选型总览

### 1.1 三套技术栈定位

平台由三个独立进程构成，各自承担不同职责，技术栈选型基于职责特征而非统一标准。C 端应用后端以 AI 对话和高并发长连接为核心，选择 Spring Boot 3.5.x 叠加 Spring AI Alibaba；B 端 ERP 以数据采集和治理为核心，同样基于 Spring Boot 但不需要 AI 能力；计算服务以数值计算为核心，选择 Python FastAPI 发挥科学计算生态优势。三套技术栈通过 HTTP/JSON 通信，共享 MySQL 与 Redis，各自独立演进。

### 1.2 选型原则

技术选型遵循四条原则，按优先级排序。第一，**成熟稳定优先**：选择生产环境验证过的稳定版本，不追逐最新 release candidate 或 beta 版本，降低踩坑概率。第二，**社区活跃度**：选择文档完善、社区活跃、问题可搜索到解决方案的组件，避免选型冷门技术导致排错困难。第三，**与 stock-pulse 技术栈一致性**：新平台虽然从零构建全新代码库，但在 Tushare 数据架构、计算服务通信模式、MyBatis-Plus 使用方式上借鉴 stock-pulse 的成熟经验，相同技术栈能显著降低团队学习成本和调试成本。第四，**Java 生态优先**：在功能等价的前提下，优先选择 Java 生态的解决方案（如 Spring AI Alibaba 而非 LangChain4j），保持后端技术栈的统一性。

需要特别说明的是 Spring Boot 版本选择。stock-pulse 使用 Spring Boot 4.0.6，但新平台 C 端应用必须使用 **Spring Boot 3.5.x**，原因是 Spring AI Alibaba 1.1.2.0 要求 Spring Boot 3.5.x，而 Spring Boot 4.x 尚未被 Spring AI Alibaba 支持。这是一个硬性兼容约束，C 端应用的技术栈必须围绕 Spring AI Alibaba 的版本要求来组织。

### 1.3 三套技术栈选型对比

| 维度 | C 端应用后端 | B 端 ERP 系统 | 计算服务 |
|------|-------------|---------------|----------|
| **语言** | Java 21 | Java 21 | Python 3.12 |
| **框架** | Spring Boot 3.5.x | Spring Boot 3.5.x | FastAPI 0.115.x |
| **AI 框架** | Spring AI Alibaba 1.1.2.0 | 无 | 无 |
| **ORM** | MyBatis-Plus 3.5.x | MyBatis-Plus 3.5.x | 无（不触库） |
| **缓存** | Redis 7.x + Caffeine | Redis 7.x + Caffeine | 无 |
| **向量存储** | Redis Stack（RediSearch） | 无 | 无 |
| **定时任务** | Quartz | Quartz | 无 |
| **实时通信** | WebSocket + SSE | 无 | 无 |
| **模型服务** | 通义千问（DashScope API） | 无 | 无 |
| **前端** | 原生 JS + ECharts 5 | Thymeleaf + Bootstrap 5 + ECharts 5 | 无 |
| **数据处理** | 无 | 无 | Pandas + NumPy + talib |
| **端口** | :8080 | :8081 | :8085 |
| **数据库权限** | Tushare 表只读 + 用户表读写 | 全量读写 | 不触库 |

---

## 二、C 端应用后端技术栈

### 2.1 技术栈总览

C 端应用后端是平台的技术核心，承担 AI 对话、用户业务、实时推送三大职责。技术栈围绕 Spring AI Alibaba 构建，每个组件的选择都有明确的职责边界。

| 组件 | 选型 | 版本 | 职责 |
|------|------|------|------|
| JDK | OpenJDK 21 (LTS) | 21+ | 虚拟线程支撑高并发 SSE/WebSocket |
| 框架 | Spring Boot | 3.5.x | Web 框架 + 自动配置 + 依赖管理 |
| AI 框架 | Spring AI Alibaba | 1.1.2.0 | ChatClient/Graph/ChatMemory/Function Calling/RAG |
| 模型服务 | 通义千问 DashScope | API | qwen-flash / qwen-plus / qwen3-plus 分级调用 |
| ORM | MyBatis-Plus | 3.5.x | 数据持久化 + 只读查询 |
| 分布式缓存 | Redis | 7.x | 会话缓存 + 向量索引 + 行情缓存 + 任务队列 |
| 本地缓存 | Caffeine | 随 Spring Boot | 热点数据本地缓存，降低 Redis 压力 |
| 向量存储 | Redis Stack (RediSearch) | 7.4+ | RAG 知识库向量检索 |
| 定时任务 | Quartz | 随 Spring Boot | 用户自定义盯盘规则与定时任务调度 |
| 实时推送 | WebSocket | 随 Spring Boot | 盯盘通知、任务结果实时推送 |
| 流式对话 | SSE (SseEmitter) | 随 Spring Boot | AI 回复打字机效果流式输出 |
| JSON 处理 | FastJSON2 | 2.0.x | Tushare 数据解析、HTTP 通信序列化 |
| API 文档 | springdoc-openapi | 2.8.x | OpenAPI 文档自动生成 |
| 工具库 | Hutool | 5.8.x | 通用工具方法 |

### 2.2 JDK 21 与虚拟线程

选择 JDK 21 的核心驱动力是**虚拟线程（Virtual Thread）**。C 端应用同时维护大量 SSE 和 WebSocket 长连接，传统平台线程模型下每个连接占用一个 OS 线程，200 个并发用户就需要 200 个平台线程，线程切换和内存开销成为性能瓶颈。

虚拟线程由 JVM 在用户态调度，阻塞时自动让出载体线程（carrier thread），一个载体线程可以交替执行数百个虚拟线程。单机即可承载数万并发长连接，而 OS 线程数仅需几十个。这对 SSE 流式对话和 WebSocket 实时推送两个高并发长连接场景是关键优势：用户在对话页面等待 AI 逐 token 输出时，SSE 连接会阻塞数秒，虚拟线程让这段阻塞不占用珍贵的 OS 线程。

启用虚拟线程有两种方式。第一种是全局开启，在 `application.yml` 中配置 `spring.threads.virtual.enabled: true`，Spring Boot 会自动用虚拟线程处理所有 HTTP 请求和异步任务。第二种是手动创建，通过 `Thread.ofVirtual().start()` 在需要的地方显式使用。推荐全局开启为主，对 Quartz 调度线程等特殊场景再手动控制。

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

JDK 21 的其他增益包括**模式匹配**简化 if-else 链、**Record 类**替代 DTO 样板代码、**Switch 表达式**让意图路由更简洁。选择 OpenJDK 发行版时，推荐 Eclipse Temurin（Adoptium）或 Amazon Corretto，两者都提供 LTS 支持和容器化友好镜像。

### 2.3 Spring Boot 3.5.x

C 端应用和 B 端 ERP 都使用 Spring Boot，但版本必须锁定在 **3.5.x**。这个版本约束来自 Spring AI Alibaba 的兼容性要求：Spring AI Alibaba 1.1.2.0 依赖 Spring AI 1.1.2，而 Spring AI 1.1.2 要求 Spring Boot 3.5.x。如果 C 端应用使用 Spring Boot 4.x，Spring AI Alibaba 无法正常工作。

选择 Spring Boot 而非其他 Java Web 框架（如 Quarkus、Micronaut）的理由有三条。第一，**Spring 生态完整性**：Spring Boot 与 Spring Security、Spring Data、Spring Cache 等组件无缝集成，覆盖 Web、安全、缓存、定时任务等全部需求。第二，**Spring AI Alibaba 原生支持**：Spring AI Alibaba 基于 Spring Framework 构建，与 Spring Boot 的自动配置深度集成，使用其他框架无法接入。第三，**与 stock-pulse 一致**：虽然 stock-pulse 用的是 Spring Boot 4.0.6，但 API 层面 3.5.x 与 4.x 差异不大，团队能快速迁移经验。

关键配置要点包括：通过 `spring-boot-starter-parent` 统一管理 Spring 全家桶版本；启用 `spring-boot-starter-actuator` 暴露健康检查和指标；启用 `spring-boot-starter-validation` 做 DTO 参数校验；启用 `spring-boot-devtools` 加速本地开发热重载。

### 2.4 Spring AI Alibaba 1.1.2.0

Spring AI Alibaba（简称 SAA）是 C 端 AI 对话中枢的**底层框架**，由阿里中间件团队维护，基于 Spring AI 构建，深度适配通义千问和 DashScope。选择 SAA 而非 LangChain4j 或直接调用 DashScope API 的理由是：SAA 是阿里官方维护的 Spring AI 扩展，对通义千问的流式输出、Function Calling、多模态等能力有原生适配，且提供 Graph 工作流编排能力，这是 LangChain4j 不具备的。

版本兼容关系必须严格对齐。SAA 1.1.2.0 依赖 Spring AI 1.1.2 和 Spring AI Extensions 1.1.2.1，三者通过 BOM 统一管理，不能混用不同版本。推荐通过 BOM 引入依赖，避免手动指定版本号导致冲突：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-bom</artifactId>
            <version>1.1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.1.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-extensions-bom</artifactId>
            <version>1.1.2.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

实际依赖只需引入两个核心 artifact：

```xml
<dependencies>
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-graph-core</artifactId>
    </dependency>
</dependencies>
```

SAA 的核心 API 清单如下，每个组件对应对话中枢的一个子系统：

| SAA 组件 | 核心 API | 对话中枢职责 | 版本要求 |
|----------|----------|-------------|----------|
| **ChatClient** | `ChatClient.create()` / `.prompt()` / `.stream()` / `.call()` | 封装通义千问调用，统一 System Prompt、流式输出、模型分级 | SAA 1.1.2.0 |
| **Graph** | `StateGraph` / `GraphBuilder` / `node()` / `edge()` / `branch()` | 18 种研究方法映射为节点，Workflow 编排串联/并行/条件分支 | graph-core 同 BOM |
| **ChatMemory** | `ChatMemory` / `MessageWindowChatMemory` / `InMemoryChatMemoryStore` | 按 conversationId 维护对话上下文，窗口 20 条，超窗口摘要 | Spring AI 1.1.2 |
| **Function Calling** | `@Tool` / `ToolCallback` / `ToolSpecification` | 16 个工具注册为可调用函数，AI 自动选择并组装参数 | Spring AI 1.1.2 |
| **RAG** | `VectorStore` / `EmbeddingModel` / `DocumentReader` / `DocumentSplitter` | 三个向量索引（方法论、历史报告、公司基本面）的构建与检索 | Spring AI 1.1.2 |
| **Advisor** | `Advisor` / `RequestResponseAdvisor` / `ChatClientAdvisor` | 合规过滤、数据校验等横切逻辑，拦截或改写 AI 输出 | Spring AI 1.1.2 |

ChatClient 是所有模型调用的统一入口。轻量查询场景调用 qwen-flash 控制成本，深度分析场景调用 qwen-plus 保证质量，多步骤研究报告调用 qwen3-plus 做复杂推理。通过 `.stream()` 方法获取流式输出，逐 token 推送给前端 SSE 连接，实现打字机效果。

Graph 是多步骤研究的编排引擎。每个研究方法封装为一个 `node()`，通过 `edge()` 连接节点定义执行顺序，通过 `branch()` 实现条件分支。例如"全面分析茅台"的 Workflow 会编排 tear-sheet、技术面、估值、资金面、风险提示六个节点，节点间可串联也可并行。

ChatMemory 需要自定义持久化实现。SAA 自带的 `InMemoryChatMemoryStore` 不满足持久化需求，需要实现基于 MySQL 的 `ChatMemoryStore`，将对话消息写入 `chat_message` 表，按 conversationId 隔离，窗口保留最近 20 条消息。

### 2.5 MyBatis-Plus

MyBatis-Plus 选择与 stock-pulse 一致的方案，版本锁定 **3.5.x**。需要注意的是，Spring Boot 3.5.x 对应的 starter artifact 是 `mybatis-plus-spring-boot3-starter`（stock-pulse 用的是 `mybatis-plus-spring-boot4-starter`，两者不能混用）。

选择 MyBatis-Plus 而非 JPA/Hibernate 的理由有三条。第一，**SQL 可控性**：Tushare 数据表有 25+ 张，字段多且查询模式复杂，MyBatis-Plus 的 QueryWrapper 提供灵活的条件构造，复杂查询可退回 XML 写原生 SQL，JPA 的 JPQL 在复杂查询场景下反而笨重。第二，**与 stock-pulse 一致**：stock-pulse 的 Tushare 六层架构基于 MyBatis-Plus 构建，DO 对象、Mapper 接口、批量写入等模式可以直接参考。第三，**分页插件**：MyBatis-Plus 自带 PaginationInnerInterceptor，无需额外引入 PageHelper。

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.15</version>
</dependency>
```

关键配置要点：在 `@MapperScan` 中指定 Mapper 包路径；配置 `MybatisPlusInterceptor` 注册分页插件；Tushare 业务表的 Mapper 只暴露 select 方法不提供写操作；用户业务表的 Mapper 提供完整 CRUD；所有 DO 对象使用 `@TableName` 显式映射表名，不依赖驼峰转下划线的默认推断。

### 2.6 Redis + Caffeine 双层缓存

缓存采用 **Caffeine 本地缓存 + Redis 分布式缓存**的双层架构。Caffeine 作为 L1 缓存放在 JVM 进程内，Redis 作为 L2 缓存在进程外共享。查询路径是 Caffeine -> Redis -> MySQL，逐级回源。

选择 Caffeine 而非 Guava Cache 的理由是 Caffeine 基于 W-TinyLFU 算法，缓存命中率显著高于 Guava 的 LRU，且已被 Spring Boot 作为默认本地缓存实现。热点行情数据（如当日大盘指数、热门股票实时价）先查 Caffeine，未命中再查 Redis，最后回源 MySQL。行情数据 TTL 设为当日有效，估值数据 TTL 设为 1 小时。

选择 Redis 而非 Memcached 的理由是 Redis 支持丰富的数据结构（String/Hash/List/Set/ZSet）和 Redis Stack 模块（RediSearch 向量检索），单一组件同时承担缓存、向量索引、任务队列三种职责，减少中间件数量。

### 2.7 Redis Stack 与 RediSearch

向量存储选择 **Redis Stack** 的 RediSearch 模块，而非独立的向量数据库（如 Milvus、Qdrant）。理由有三条。第一，**降低运维成本**：平台初期单体部署，引入独立向量数据库会增加运维负担。Redis Stack 在已有 Redis 基础上加载 RediSearch 模块即可，无需额外进程。第二，**数据量匹配**：三个 RAG 知识库（投资方法论、历史报告、公司基本面）的数据量在万级到十万级文档，RediSearch 的 HNSW 和 FLAT 索引完全够用，不需要 Milvus 那种亿级向量的分布式方案。第三，**与 Spring AI 集成**：Spring AI 提供 `RedisVectorStore` 实现，与 SAA 的 RAG 组件无缝对接。

Redis Stack 版本要求 **7.4+**（内置 RediSearch 2.x 支持向量类型）。安装方式有三种：Docker 镜像 `redis/redis-stack-server`、Linux 包管理器安装、源码编译加载模块。推荐 Docker 方式，简化环境搭建：

```bash
docker run -d --name redis-stack \
  -p 6379:6379 \
  redis/redis-stack-server:latest
```

RediSearch 向量索引创建示例（投资方法论库）：

```python
# 通过 redis-cli 或 RedisInsight 创建
FT.CREATE methodology_idx ON HASH PREFIX 1 methodology: SCHEMA
  content TEXT
  embedding VECTOR HNSW 6 TYPE FLOAT32 DIM 1536 DISTANCE_METRIC COSINE
```

其中 DIM 1536 对应通义千问 text-embedding-v3 的向量维度。如果使用其他 embedding 模型，需要调整 DIM 参数。

### 2.8 Quartz 定时任务

定时任务选择 **Quartz**，而非 Spring 的 `@Scheduled` 注解。理由是 C 端应用需要支持用户自定义 cron 表达式（如"每天早上 8 点发茅台晨报"），`@Scheduled` 只能在代码中硬编码 cron，Quartz 支持运行时动态注册和修改任务。

Quartz 的 job 数据存储选择 JDBC JobStore（持久化到 MySQL），而非 RAMJobStore。理由是用户创建的定时任务必须持久化，服务重启后任务不能丢失。需要 Quartz 对应的数据表（`qrtz_*` 系列），通过 Quartz 提供的 DDL 脚本初始化。

关键配置要点：Quartz 调度线程池独立于 HTTP 请求线程池，避免定时任务耗尽请求线程导致 API 无响应；盯盘引擎的取数和 AI 分析是耗时操作，应异步执行，结果通过 Redis 队列缓冲后推送，不阻塞调度线程。

### 2.9 WebSocket 与 SSE

实时通信选择 **WebSocket + SSE 双通道**方案。WebSocket 用于服务端主动推送（盯盘触发通知、定时任务完成推送、消息中心铃铛红点），SSE 用于 AI 对话流式输出。

选择 SSE 而非 WebSocket 做对话流式输出的理由有三条。第一，**协议简单**：SSE 基于标准 HTTP，不需要 WebSocket 的握手升级，通过 Spring 的 `SseEmitter` 即可实现。第二，**浏览器兼容性好**：EventSource API 原生支持，无需引入额外库。第三，**单向通信足够**：对话流式输出只需要服务端推、客户端收，不需要客户端在流式过程中发送消息，WebSocket 的双向能力是多余的。

Spring Boot 3.5.x 的 `SseEmitter` 配合虚拟线程，能让每个 SSE 连接挂在一个虚拟线程上，阻塞等待模型输出时不占用 OS 线程。WebSocket 端点通过 `@ServerEndpoint` 或 Spring 的 `WebSocketHandler` 实现，连接建立时绑定 userId，推送时按 userId 查找在线连接。

### 2.10 通义千问 DashScope

模型服务选择**通义千问**，通过阿里云百炼平台的 DashScope API 调用。选择通义千问而非 OpenAI GPT 或其他模型，理由有三条。第一，**Spring AI Alibaba 原生适配**：SAA 的 `spring-ai-alibaba-starter-dashscope` 直接封装了 DashScope API，无需自己写 HTTP 客户端。第二，**中文能力强**：平台面向 A 股投资者，对话和报告以中文为主，通义千问的中文理解和生成能力在国产模型中名列前茅。第三，**成本可控**：百炼平台新用户有免费额度，且模型分级策略（qwen-flash 做轻量查询、qwen-plus 做均衡分析、qwen3-plus 做深度推理）能有效控制成本。

模型分级策略如下：

| 模型 | 用途 | 调用场景 | 成本特征 |
|------|------|----------|----------|
| qwen-flash | 超轻量 | 意图路由、简单问答、参数解析 | 最低成本 |
| qwen-plus | 均衡分析 | 单方法分析、报告节点解读、盯盘简评 | 中等成本 |
| qwen3-plus | 深度推理 | 多步骤研究报告、复杂逻辑推理 | 较高成本 |

配置方式通过 `application.yml` 设置 API Key 和默认模型，在 ChatClient 调用时通过 `.model()` 方法按场景切换模型。

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen-plus
```

---

## 三、B 端 ERP 系统技术栈

### 3.1 技术栈总览

B 端 ERP 是面向运维和管理员的数据管理后台，核心职责是 Tushare 数据采集、质量检测和治理。技术栈与 C 端共享大部分组件，但不包含任何 AI 能力。

| 组件 | 选型 | 版本 | 职责 |
|------|------|------|------|
| JDK | OpenJDK 21 | 21+ | 与 C 端一致 |
| 框架 | Spring Boot | 3.5.x | 与 C 端一致 |
| ORM | MyBatis-Plus | 3.5.x | Tushare 六层架构持久化 |
| 前端模板 | Thymeleaf | 随 Spring Boot | SSR 管理页面 |
| UI 框架 | Bootstrap | 5.x | 管理页面样式 |
| 图表 | ECharts | 5.x | 数据可视化 |
| 定时任务 | Quartz | 随 Spring Boot | Tushare 数据采集调度 |
| 缓存 | Caffeine | 随 Spring Boot | 配置缓存 |
| JSON 处理 | FastJSON2 | 2.0.x | Tushare 接口响应解析 |
| 工具库 | Hutool | 5.8.x | 通用工具方法 |

### 3.2 Tushare 六层架构对应的技术组件

ERP 的核心是参考 stock-pulse 的 **Tushare 六层数据架构**，每层对应不同的技术组件：

| 架构层 | 技术组件 | 职责 |
|--------|----------|------|
| 第1层 Config + TushareApiEnum | Java 枚举 + FastJSON2 | 接口元数据定义（api_name + fields 字符串） |
| 第2层 Client + RateLimiter | OkHttp/Hutool HttpUtil + Guava RateLimiter | 统一请求入口 + 阻塞式限流 + 分页回调 |
| 第3层 DO + Mapper | MyBatis-Plus DO + BaseMapper | 数据对象 + 持久化（schema.sql 定义表结构） |
| 第4层 Service | Spring Service + DataCheckable 接口 | 分页拉取 + 批量保存 + 质量校验 |
| 第5层 DataInitService | Spring Service | 增量/全量入口 + 写拉取日志 |
| 第6层 DataGovernanceController | Spring MVC Controller + Thymeleaf | 数据治理 REST 接口 + 管理页面 |

TushareClient 封装了 HTTP 请求组装、POST 调用、fields[]+items[][] 二维数组解析和 DTO 映射。Tushare API 的响应格式是 `{"fields": [...], "items": [[...], [...]]}`，需要用 FastJSON2 解析后转为 DO 对象列表。RateLimiter 使用 Guava 的 `Semaphore` 做阻塞式限流，防止触发 Tushare 的 429 限流响应。

### 3.3 与 C 端共享组件的版本对齐

ERP 与 C 端共享多个技术组件，版本必须严格对齐。下表列出共享组件的对齐要求：

| 共享组件 | 对齐版本 | 对齐原因 |
|----------|----------|----------|
| JDK | 21 | 同一团队，避免多版本 JDK 切换 |
| Spring Boot | 3.5.x | 共享 Spring 全家桶经验，BOM 统一 |
| MyBatis-Plus | 3.5.15 | 共享 DO/Mapper 设计模式，版本一致便于参考 |
| FastJSON2 | 2.0.61 | Tushare 数据解析格式一致 |
| Hutool | 5.8.44 | 工具方法一致，避免版本差异导致的 API 不兼容 |
| Quartz | 随 Spring Boot | 定时任务调度模式一致 |
| MySQL Connector | 随 Spring Boot | 数据库连接驱动版本一致 |

ERP 虽然不需要 Spring AI Alibaba，但 Spring Boot 版本仍然锁定 3.5.x。这样做的理由是保持两个项目的技术栈一致性，降低团队上下文切换成本，且共享组件的版本对齐不会产生冲突。ERP 的 pom.xml 不引入 SAA 相关依赖，只保留基础 Spring Boot starter。

---

## 四、计算服务技术栈

### 4.1 技术栈总览

计算服务是一个独立的 Python 进程，承担技术指标计算、因子计算和统计分析职责。技术栈参考 stock-pulse 的 stock-engine 设计。

| 组件 | 选型 | 版本 | 职责 |
|------|------|------|------|
| Python | CPython | 3.12 | 运行时 |
| Web 框架 | FastAPI | 0.115.x | HTTP API 服务 |
| ASGI 服务器 | Uvicorn | 0.41.x | 生产级 ASGI 服务器 |
| 数据处理 | Pandas | 2.x/3.x | DataFrame 数据处理 |
| 数值计算 | NumPy | 2.x | 向量化计算 |
| 技术指标 | TA-Lib | 0.4.x | MA/MACD/KDJ/BOLL/RSI/ATR |
| 数据校验 | Pydantic | 2.x | 请求/响应模型定义 |
| 配置管理 | pydantic-settings | 2.x | 环境变量配置 |
| HTTP 客户端 | requests | 2.32.x | 内部调用（如查询 watcher 只读接口） |
| 日志 | loguru | 0.7.x | 结构化日志 |
| API 文档 | FastAPI 自动生成 | - | OpenAPI 文档 :8085/docs |

### 4.2 FastAPI 与 Spring Boot 的 HTTP 通信协议设计

计算服务与 C 端后端通过 **HTTP/JSON** 通信，遵循数据单源性原则：数据库的读写由 Java 侧独占，Python 计算服务只做纯计算，不直接接触数据库。

通信协议设计如下。C 端后端把行情数据或参数通过 HTTP POST 传入计算服务，请求体包含计算所需的原始数据和参数；计算服务用 Pandas/talib 计算后返回 JSON 结果；C 端后端接收结果后组装富媒体容器或存入报告。

```
C 端后端 (Java :8080)
    │
    │  1. 从 MySQL 取行情数据
    │  2. HTTP POST 传入计算服务
    │     POST /api/calculate/indicator
    │     Body: {kline_data: [...], indicator: "MACD", params: {...}}
    │
    ▼
计算服务 (Python :8085)
    │
    │  3. Pydantic 校验请求参数
    │  4. Pandas/talib 计算指标
    │  5. 返回 JSON 结果
    │     Response: {indicator: "MACD", values: [...], chart_data: {...}}
    │
    ▼
C 端后端 (Java :8080)
    │
    │  6. 组装富媒体容器 / 存入报告
```

接口设计遵循 FastAPI 最佳实践。路由层只做参数校验和响应封装，核心逻辑放在 service 层，数据模型用 Pydantic Schema 定义。统一响应格式与 C 端的 `ApiResponse` 对齐，包含 `code`、`message`、`data` 三个字段，方便 Java 侧统一解析。FastAPI 自动生成 OpenAPI 文档（:8085/docs），C 端后端开发人员可直接查看接口定义进行联调。

```python
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="Stock Engine", docs_url="/docs")

class IndicatorRequest(BaseModel):
    kline_data: list[dict]
    indicator: str
    params: dict = {}

class IndicatorResponse(BaseModel):
    code: int = 200
    message: str = "success"
    data: dict

@app.post("/api/calculate/indicator", response_model=IndicatorResponse)
async def calculate_indicator(req: IndicatorRequest):
    # 路由层只做参数校验，逻辑在 service 层
    result = indicator_service.calculate(req.kline_data, req.indicator, req.params)
    return IndicatorResponse(data=result)
```

### 4.3 Python 环境要求

计算服务要求 **Python 3.12**，通过 conda 环境管理依赖。选择 Python 3.12 而非 3.11 或 3.13 的理由是：3.12 的性能优化（特化解释器）对 Pandas/NumPy 的密集计算有实际增益，且与 stock-pulse 的 stock-engine 环境保持一致。

依赖管理使用 **requirements.txt + conda 环境**的组合方案。conda 负责创建隔离的 Python 环境（`conda create -n stock python=3.12`），requirements.txt 负责锁定依赖版本。这种方式与 stock-pulse 完全一致，团队无需学习新的依赖管理工具。

```bash
# 创建 conda 环境
conda create -n stock python=3.12 -y

# 激活环境并安装依赖
conda activate stock
pip install -r requirements.txt
```

### 4.4 TA-Lib 安装注意事项

**TA-Lib** 是技术指标计算的核心库，但其安装有一个常见坑：它依赖底层的 C 语言库 `ta-lib`，必须先安装 C 库再安装 Python 包，否则 `pip install TA-Lib` 会编译报错。

不同操作系统的 C 库安装方式不同。Windows 下推荐下载预编译的 wheel 包（whl 文件）直接安装，避免编译失败。Linux 下通过包管理器安装：Ubuntu/Debian 用 `apt-get install ta-lib`，CentOS 需从源码编译。macOS 下用 Homebrew：`brew install ta-lib`。

```bash
# Linux (Ubuntu/Debian)
sudo apt-get install -y ta-lib

# macOS
brew install ta-lib

# 安装 Python 包
pip install TA-Lib

# Windows - 下载预编译 wheel
pip install TA_Lib-0.4.x-cp312-cp312-win_amd64.whl
```

如果安装失败，替代方案是使用 **pandas-ta** 或 **talipp** 等纯 Python 实现的指标库。这些库不依赖 C 库，安装简单，但计算性能不如 TA-Lib。对于日线级别的技术指标计算（非高频），性能差异不构成瓶颈，可作为降级方案。

---

## 五、中间件与基础设施

### 5.1 MySQL 选型与配置

MySQL 作为平台的**核心关系型数据库**，承载 Tushare 业务表（25+ 张）和 C 端用户业务表（9 张）。选择 MySQL 而非 PostgreSQL 的理由是与 stock-pulse 一致（stock-pulse 已在 MySQL 上验证了 Tushare 六层架构），且 MySQL 的运维生态更成熟。

版本要求 **MySQL 8.0+**。MySQL 8.0 相比 5.7 的关键增益包括：JSON 类型原生支持（用于 `rule_params`、`params_json` 等 JSON 字段）、窗口函数（用于 PE 分位等排名计算）、降序索引优化。不使用 MySQL 8.4 LTS 的原因是部分驱动和工具的兼容性仍在验证中，8.0 是经过充分验证的稳定版本。

字符集配置统一使用 `utf8mb4`，支持完整的 Unicode 字符（包括 emoji 和生僻字）。排序规则使用 `utf8mb4_0900_ai_ci`（MySQL 8.0 默认），支持 Unicode 9.0 标准的排序和大小写不敏感比较。

```ini
# my.cnf 关键配置
[mysqld]
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
max_connections=200
innodb_buffer_pool_size=2G
innodb_log_file_size=512M
innodb_flush_log_at_trx_commit=2
```

连接池使用 **HikariCP**（Spring Boot 默认连接池），配置要点如下。`maximum-pool-size` 根据 C 端并发量设置，初期 20 个连接足够。`connection-timeout` 设为 30 秒，避免数据库不可用时请求长时间挂起。`idle-timeout` 设为 10 分钟，回收空闲连接。C 端和 ERP 各自维护独立的连接池，总连接数不超过 MySQL 的 `max_connections` 上限。

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

### 5.2 Redis 选型与配置

Redis 在平台中承担**四类职责**：会话缓存、向量索引、实时行情缓存、任务队列。选择 Redis 7.x 而非 6.x 的理由是 Redis 7.0 引入了 Function 特性（可编程性增强）和 ACL 多用户权限控制，且 Redis Stack（含 RediSearch）对 7.x 的支持更完善。

版本要求 **Redis 7.4+**，配合 Redis Stack 加载 RediSearch 模块。生产环境推荐使用 `redis/redis-stack-server` Docker 镜像，已内置 RediSearch、RedisJSON 等模块。

持久化策略采用 **RDB + AOF 混合模式**。RDB 做全量快照（每 15 分钟一次），AOF 做增量日志（每秒 fsync）。RDB 用于灾难恢复（快速加载全量数据），AOF 用于减少数据丢失（最多丢失 1 秒数据）。纯缓存场景可以关闭持久化，但平台的会话缓存和任务队列需要持久化保障，因此开启混合模式。

```ini
# redis.conf 关键配置
maxmemory 2gb
maxmemory-policy allkeys-lru
save 900 1
save 300 10
save 60 10000
appendonly yes
appendfsync everysec
```

内存配置方面，`maxmemory` 根据服务器内存设置，建议不超过物理内存的 50%。`maxmemory-policy` 设为 `allkeys-lru`，内存满时淘汰最近最少使用的 Key。向量索引数据不应被淘汰，生产环境可考虑将向量索引部署在独立的 Redis 实例中，与缓存实例隔离。

### 5.3 Redis Stack 与 RediSearch 配置

Redis Stack 的安装已在 2.7 节说明。这里补充 RediSearch 向量索引的配置要点。

平台维护三个向量索引，分别对应三个 RAG 知识库。每个索引的创建需要指定向量维度、距离度量和索引算法。通义千问的 `text-embedding-v3` 模型输出 1536 维向量，因此所有索引的 DIM 参数统一为 1536。距离度量使用 **COSINE**（余弦相似度），适合文本语义检索。索引算法选择 **HNSW**（分层可导航小世界图），在召回率和查询延迟之间取得平衡。

| 索引名称 | 前缀 | 用途 | 文档量级 |
|----------|------|------|----------|
| methodology_idx | methodology: | 投资方法论库 | 万级 |
| report_idx | report: | 历史报告库 | 十万级（随用户增长） |
| company_idx | company: | 公司基本面库 | 万级 |

向量数据的写入和检索通过 Spring AI 的 `RedisVectorStore` 实现对接，Java 侧不需要手写 RediSearch 命令。`RedisVectorStore` 封装了向量的添加、删除和相似度检索，底层自动调用 RediSearch 的 `FT.CREATE`、`FT.SEARCH` 等命令。

### 5.4 JDK 21 JVM 参数建议

JDK 21 的 JVM 配置需要针对虚拟线程和 GC 策略做优化。

**虚拟线程配置**：Spring Boot 3.5.x 通过 `spring.threads.virtual.enabled=true` 全局开启虚拟线程后，JDK 21 默认的载体线程数为 CPU 核心数。可以通过 `-Djdk.virtualThreadScheduler.parallelism` 调整，建议设为 CPU 核心数的 2 倍，让载体线程有更多并行能力。

**GC 策略**：JDK 21 推荐 **G1 GC**（默认垃圾收集器），适用于大堆内存和多核服务器。G1 的停顿时间目标通过 `-XX:MaxGCPauseMillis=200` 设置为 200ms，满足 SSE 流式对话的低延迟要求。如果堆内存超过 16GB，可考虑 **ZGC**（JDK 21 中已正式可用），停顿时间在亚毫秒级，但吞吐量略低于 G1。

```bash
# 生产环境 JVM 参数建议
java -server \
  -Xms4g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/stock-app/heapdump.hprof \
  -Djdk.virtualThreadScheduler.parallelism=8 \
  -Dspring.threads.virtual.enabled=true \
  -jar stock-app.jar
```

`-Xms` 和 `-Xmx` 设为相同值，避免堆内存动态扩展时的性能波动。初始堆和最大堆都设为 4GB，适合 8GB 内存的服务器（剩余内存留给 Redis、MySQL、系统）。`HeapDumpOnOutOfMemoryError` 开启 OOM 时自动 dump 堆内存，便于事后排查。

---

## 六、前端技术栈

### 6.1 技术栈总览

前端技术栈按 C 端应用和 B 端 ERP 区分。C 端应用初期采用原生 JS + 模块化方案，不引入前端框架，追求开发速度和部署简单。B 端 ERP 采用 Thymeleaf SSR + Bootstrap 的传统管理后台方案。

| 组件 | C 端选型 | B 端选型 | 版本要求 |
|------|----------|----------|----------|
| 基础框架 | 原生 JS (ES6 模块) | Thymeleaf + Bootstrap 5 | Bootstrap 5.3.x |
| 图表库 | ECharts 5 | ECharts 5 | 5.5.x |
| Markdown 渲染 | marked.js | 无 | 12.x |
| 代码高亮 | highlight.js | 无 | 11.x |
| 流式对话 | EventSource API (SSE) | 无 | 浏览器原生 |
| 实时推送 | WebSocket API | 无 | 浏览器原生 |
| 样式方案 | CSS 变量 + 组件化 | Bootstrap + 自定义 CSS | - |
| 模板引擎 | 无（原生 JS 渲染） | Thymeleaf | 随 Spring Boot |
| 后期框架 | Vue 3（Phase 4 拆分后） | 无 | 3.5.x |

### 6.2 各组件版本要求与引入方式

C 端前端初期通过 **CDN 引入**第三方库，不使用 npm 构建工具。这种方式适合 Phase 0-2 的快速开发阶段，前端资源直接放在 Spring Boot 的 `src/main/resources/static` 目录下。

```html
<!-- ECharts 5 -->
<script src="https://cdn.jsdelivr.net/npm/echarts@5.5.1/dist/echarts.min.js"></script>

<!-- marked.js (Markdown 渲染) -->
<script src="https://cdn.jsdelivr.net/npm/marked@12.0.0/marked.min.js"></script>

<!-- highlight.js (代码高亮) -->
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/highlight.js@11.9.0/styles/github.min.css">
<script src="https://cdn.jsdelivr.net/npm/highlight.js@11.9.0/lib/common.min.js"></script>
```

ECharts 5 选择与 stock-pulse 一致的版本，金融图表类型（K线图、折线图、柱状图、雷达图）在 stock-pulse 中已验证过可行性。marked.js 负责 AI 回复中的 Markdown 格式化渲染，将 `**加粗**`、`|表格|`、`列表` 等语法转为 HTML。highlight.js 负责代码块的语法高亮，在 AI 回复包含 SQL 或代码示例时启用。

EventSource API 和 WebSocket API 都是浏览器原生支持，不需要引入额外库。SSE 通过 `new EventSource('/api/chat/stream')` 建立，监听 `onmessage` 事件接收流式数据。WebSocket 通过 `new WebSocket('ws://domain/ws')` 建立，监听 `onmessage` 事件接收推送消息。

### 6.3 前端构建工具选型

前端构建工具的选择按阶段演进。**初期（Phase 0-3）**不使用构建工具，前端资源通过 CDN 引入第三方库，自定义 JS/CSS 直接放在 static 目录下。优势是开发快、调试简单、无构建步骤，改了代码刷新即生效。这个阶段的前端复杂度不高，原生 JS 的 ES6 模块化（`import`/`export`）已经足够管理组件。

**后期（Phase 4+）**当前端拆分为独立 Vue 3 项目时，引入 **Vite** 作为构建工具。选择 Vite 而非 Webpack 的理由是 Vite 的开发体验更好（基于 ESM 的按需编译，冷启动快），且 Vue 3 官方推荐 Vite。迁移到 Vite 后，第三方库从 CDN 引入切换为 npm 安装，通过 `import` 语句管理依赖。

### 6.4 移动端适配方案

C 端应用面向个人投资者，移动端适配是刚需。初期采用 **响应式布局**方案，通过 CSS 媒体查询适配不同屏幕尺寸，不单独开发移动端应用。

CSS 变量（Custom Properties）构建设计令牌体系，统一管理颜色、间距、字号等视觉变量。通过媒体查询在移动端和桌面端切换变量值，实现一套代码适配两端。

```css
:root {
  --chat-max-width: 768px;
  --sidebar-width: 240px;
  --font-size-base: 14px;
}

@media (max-width: 768px) {
  :root {
    --sidebar-width: 0px;  /* 移动端隐藏侧边栏 */
    --font-size-base: 13px;
  }
}
```

移动端的关键适配点包括：左侧边栏在移动端默认隐藏，通过汉堡菜单切换显示；对话区域铺满屏幕宽度，股票卡片自适应缩放；K 线图和图表使用 ECharts 的 `resize` 方法响应屏幕尺寸变化；输入框区域固定在底部，不被虚拟键盘遮挡。

---

## 七、开发与部署环境

### 7.1 开发环境配置

开发环境需要同时支持 Java 和 Python 两套技术栈。以下是推荐的开发环境配置清单：

| 工具 | 推荐选型 | 版本 | 用途 |
|------|----------|------|------|
| JDK | Eclipse Temurin / Amazon Corretto | 21 (LTS) | Java 运行时 |
| 构建工具 | Maven (mvnw) | 3.9.x | Java 依赖管理与构建 |
| Python | CPython (conda) | 3.12 | 计算服务运行时 |
| Conda | Miniforge / Miniconda | 最新 | Python 环境管理 |
| MySQL | MySQL Community Server | 8.0+ | 本地数据库 |
| Redis | Redis Stack (Docker) | 7.4+ | 本地缓存与向量 |
| IDE (Java) | IntelliJ IDEA Ultimate | 2024.x+ | Java 开发（Community 也可） |
| IDE (Python) | PyCharm / VS Code | 最新 | Python 开发 |
| API 调试 | IDEA HTTP Client / Bruno | - | 接口联调 |
| 数据库工具 | DBeaver / DataGrip | 最新 | 数据库管理 |
| Redis 工具 | RedisInsight | 最新 | Redis 数据与向量索引管理 |
| 容器工具 | Docker Desktop | 最新 | Redis Stack / MySQL 容器化 |

IDE 推荐使用 IntelliJ IDEA Ultimate，支持 Spring Boot、Thymeleaf、Database 工具集成和 HTTP Client，一个 IDE 覆盖 Java 开发、数据库管理和接口调试三大场景。Python 开发可使用 PyCharm（与 IDEA 同属 JetBrains，快捷键一致）或 VS Code（轻量级，插件丰富）。

### 7.2 本地开发启动流程

本地开发需要同时启动三个进程：C 端后端（:8080）、B 端 ERP（:8081）、计算服务（:8085）。参考 stock-pulse 的 `run.js` 一键启动方案，新平台同样编写统一的启动脚本管理三个进程。

**首次环境搭建流程**：

```bash
# 1. 创建 conda 环境并安装计算服务依赖
conda create -n stock python=3.12 -y
cd stock-engine
pip install -r requirements.txt

# 2. 启动 Redis Stack（Docker）
docker run -d --name redis-stack -p 6379:6379 redis/redis-stack-server:latest

# 3. 初始化 MySQL（创建数据库 + 执行 schema.sql）
mysql -u root -p < schema.sql

# 4. 配置 C 端和 ERP 的 application-secret.properties
#    填写 dashscope.api-key / tushare.token / db.url / redis.host

# 5. 一键全栈启动
node run.js start
```

**日常开发启动**：

```bash
# 一键全栈启动（engine -> C端 -> ERP，后台运行）
node run.js start

# 开发模式（三个服务都开热重载）
node run.js start-dev

# 单独启动
node run.js start-engine    # 仅计算服务
node run.js start-app       # 仅 C 端
node run.js start-erp       # 仅 ERP

# 查看状态 / 停止 / 看日志
node run.js status
node run.js stop
node run.js logs app
```

联调时，C 端后端通过 `localhost:8085` 调用计算服务，计算服务的 API 文档在 `http://localhost:8085/docs` 查看。C 端和 ERP 共享同一个 MySQL 实例，ERP 写入 Tushare 数据后 C 端立即可读（同机 MySQL，事务提交后即可见）。

### 7.3 部署架构演进

部署架构按阶段渐进演进，避免过早引入容器化和编排复杂度。

**初期（Phase 0-3）：单体部署**。三个进程部署在同一台云服务器上，MySQL 和 Redis 自建或使用云服务。这个阶段用户量小（内测 100 人），单机性能足够，部署成本最低。C 端和计算服务通过 `localhost` 通信，延迟最低。ERP 部署在同机但仅监听内网 IP。

```
┌─────────────────────────────────┐
│       云服务器 (4核8G)            │
│                                 │
│  C 端后端 (Java :8080)          │
│  ERP 后端 (Java :8081)          │
│  计算服务 (Python :8085)        │
│  MySQL (自建 :3306)              │
│  Redis Stack (自建 :6379)        │
│  Nginx (反向代理)                │
└─────────────────────────────────┘
```

**中期（Phase 4）：容器化部署**。将三个进程分别容器化，通过 Docker Compose 编排。容器化的收益是环境一致性（开发/测试/生产环境相同）、隔离性（进程资源隔离）、迁移方便（镜像即部署单元）。MySQL 和 Redis 可使用云服务托管版，减少自建运维负担。

**远期（Phase 4+）：K8s 编排**。当用户量增长到需要多实例横向扩容时，引入 Kubernetes 做容器编排。C 端后端无状态化后可多副本部署，通过 Service 负载均衡。计算服务也可多副本部署分摊计算压力。MySQL 和 Redis 使用云服务或 K8s 内的 StatefulSet 部署。

### 7.4 环境配置管理

配置管理采用 **多环境 Profile** 方案，通过 Spring Boot 的 Profile 机制区分 dev/test/prod 三套环境。

```
src/main/resources/
├── application.yml                  # 公共配置
├── application-dev.yml              # 开发环境
├── application-test.yml             # 测试环境
├── application-prod.yml             # 生产环境
└── application-secret.properties    # 密钥（不提交 Git）
```

`application.yml` 存放与环境无关的公共配置（如 MyBatis-Plus 配置、Quartz 配置、虚拟线程开关）。各环境的 Profile 文件存放差异配置（如数据库地址、Redis 地址、模型 API Key）。敏感信息（API Key、数据库密码、Tushare Token）放在 `application-secret.properties` 中，加入 `.gitignore` 不提交版本控制。

```yaml
# application.yml (公共配置)
spring:
  threads:
    virtual:
      enabled: true
  profiles:
    active: dev  # 默认开发环境

# application-dev.yml (开发环境)
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/stock_app?useSSL=false&characterEncoding=utf8mb4
    username: root
    password: ${DB_PASSWORD}
  data:
    redis:
      host: localhost
      port: 6379
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
```

生产环境通过环境变量注入敏感配置，不硬编码在配置文件中。Docker 部署时通过 `-e` 参数传入，K8s 部署时通过 Secret + ConfigMap 注入。

### 7.5 CI/CD 流程设计

CI/CD 流程分为四个阶段：代码提交、自动测试、构建打包、部署。

**代码提交阶段**：开发者将代码推送到 Git 仓库的 feature 分支，创建 Pull Request 到 main 分支。PR 创建时触发 CI 流水线的第一阶段。

**自动测试阶段**：CI 服务器拉取代码后执行自动化测试。Java 侧执行 `mvn test` 运行单元测试和集成测试，Python 侧执行 `pytest` 运行计算服务测试。测试不通过则阻断 PR 合并。关键测试覆盖包括：Tushare 数据采集的六层链路测试、Function Calling 工具的输入输出测试、计算服务指标计算的准确性测试。

**构建打包阶段**：测试通过后执行构建。Java 侧通过 `mvn package -DskipTests` 打包为可执行 JAR，Python 侧打包为 Docker 镜像。构建产物推送至制品仓库（如 Harbor）或 Git 仓库的 Release。

**部署阶段**：初期采用手动部署，运维人员拉取构建产物后通过 SSH 部署到服务器。中期引入自动化部署，通过 Docker Compose 拉取最新镜像并重启容器。部署策略采用**滚动更新**（先启动新实例、健康检查通过后下线旧实例），避免服务中断。

```
代码提交 (Git Push / PR)
    │
    ▼
自动测试 (mvn test + pytest)
    │  ── 失败则阻断合并
    ▼
构建打包 (JAR + Docker Image)
    │
    ▼
部署 (Docker Compose / K8s)
    │
    ▼
健康检查 (Actuator /health)
```

CI/CD 工具选择 **GitHub Actions** 或 **GitLab CI**，两者都支持 YAML 声明式流水线，且与 Git 仓库原生集成。初期流水线聚焦测试和构建两个阶段，部署阶段手动触发，避免自动化部署导致线上事故。

---

## 八、依赖管理与版本控制

### 8.1 Maven 依赖管理策略

Java 侧依赖管理通过 **BOM（Bill of Materials）统一版本**。BOM 是一种特殊的 POM，通过 `dependencyManagement` 声明所有依赖的版本号，实际引入依赖时不再指定 version，确保版本一致性。

新平台需要引入三套 BOM：Spring Boot 自带的 `spring-boot-starter-parent` 管理 Spring 全家桶版本；`spring-ai-alibaba-bom` 管理 SAA 各模块版本；`spring-ai-bom` 管理 Spring AI 版本。三套 BOM 的版本必须严格匹配（见 2.4 节的兼容关系），否则会出现ClassNotFoundException 或 NoSuchMethodError。

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.x</version>
</parent>

<dependencyManagement>
    <dependencies>
        <!-- Spring AI Alibaba BOM -->
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-bom</artifactId>
            <version>1.1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <!-- Spring AI BOM -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.1.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**冲突依赖排除**是 Maven 依赖管理的常见操作。Spring AI Alibaba 可能引入与 Spring Boot 不兼容的传递依赖版本，需要通过 `<exclusions>` 排除冲突依赖。常见冲突场景包括：FastJSON2 版本冲突（SAA 与项目自引版本不一致）、Jackson 版本冲突（Spring Boot 与第三方库引入不同版本）。排查冲突使用 `mvn dependency:tree` 命令查看依赖树，定位冲突后通过 exclusion 排除。

### 8.2 Python 依赖管理

Python 侧依赖管理使用 **requirements.txt + conda 环境**的组合方案。conda 负责创建隔离的 Python 环境（`conda create -n stock python=3.12`），requirements.txt 负责锁定所有依赖的精确版本号。

```txt
# requirements.txt
fastapi==0.115.7
uvicorn==0.41.0
pandas==2.2.3
numpy==2.1.0
TA-Lib==0.4.32
pydantic==2.9.0
pydantic-settings==2.5.0
python-multipart==0.0.9
requests==2.32.3
loguru==0.7.3

# 测试依赖
pytest==8.3.0
httpx==0.27.0
```

版本锁定使用 `==` 精确指定，不使用 `>=` 或 `~=` 范围版本。理由是 Python 生态的向后兼容性不如 Java 严格，小版本升级可能引入 breaking change（如 Pandas 2.x 与 3.x 的 API 差异）。精确锁定版本确保开发、测试、生产环境一致。

TA-Lib 的版本号需要根据操作系统和 Python 版本选择对应的 wheel 包。conda 环境下也可通过 `conda install -c conda-forge ta-lib` 安装 C 库，再 `pip install TA-Lib` 安装 Python 绑定。

### 8.3 前端依赖管理

前端依赖管理按阶段区分。**初期（Phase 0-3）**通过 CDN 引入第三方库，不需要 npm 和构建工具。这种方式的优势是零配置、即时可用，劣势是依赖 CDN 可用性和版本锁定不严格。为缓解 CDN 风险，建议在 `package.json` 中记录依赖版本号，虽然不通过 npm 安装，但作为版本记录的依据。

**后期（Phase 4+）**当前端拆分为 Vue 3 项目后，切换为 **npm + Vite** 管理依赖。通过 `package.json` 和 `package-lock.json` 锁定依赖版本，通过 `import` 语句引入第三方库，Vite 负责构建和打包。

### 8.4 关键依赖版本锁定清单

以下是平台全部关键依赖的版本锁定清单，作为环境搭建和问题排查的参考基准。Java 侧版本以 Spring Boot 3.5.x 为基准，Python 侧版本与 stock-pulse 的 stock-engine 对齐。

**Java 侧版本锁定**：

| 依赖 | GroupId:ArtifactId | 版本 | 来源 |
|------|---------------------|------|------|
| Spring Boot | org.springframework.boot:spring-boot-starter-parent | 3.5.x | parent POM |
| Spring AI Alibaba | com.alibaba.cloud.ai:spring-ai-alibaba-bom | 1.1.2.0 | BOM import |
| Spring AI | org.springframework.ai:spring-ai-bom | 1.1.2 | BOM import |
| Spring AI Extensions | com.alibaba.cloud.ai:spring-ai-alibaba-extensions-bom | 1.1.2.1 | BOM import |
| MyBatis-Plus | com.baomidou:mybatis-plus-spring-boot3-starter | 3.5.15 | 显式指定 |
| MyBatis-Plus JSqlParser | com.baomidou:mybatis-plus-jsqlparser | 3.5.15 | 显式指定 |
| FastJSON2 | com.alibaba.fastjson2:fastjson2 | 2.0.61 | 显式指定 |
| Hutool | cn.hutool:hutool-all | 5.8.44 | 显式指定 |
| Commons Lang3 | org.apache.commons:commons-lang3 | 3.20.0 | 显式指定 |
| Guava | com.google.guava:guava | 33.6.0-jre | 显式指定 |
| Caffeine | com.github.ben-manes.caffeine:caffeine | 随 Spring Boot | starter 管理 |
| Lombok | org.projectlombok:lombok | 随 Spring Boot | starter 管理 |
| MySQL Connector | com.mysql:mysql-connector-j | 随 Spring Boot | starter 管理 |
| springdoc-openapi | org.springdoc:springdoc-openapi-starter-webmvc-ui | 2.8.x | 显式指定 |

**Python 侧版本锁定**：

| 依赖 | 包名 | 版本 | 用途 |
|------|------|------|------|
| FastAPI | fastapi | 0.115.7 | Web 框架 |
| Uvicorn | uvicorn | 0.41.0 | ASGI 服务器 |
| Pandas | pandas | 2.2.x | 数据处理 |
| NumPy | numpy | 2.1.x | 数值计算 |
| TA-Lib | TA-Lib | 0.4.32 | 技术指标 |
| Pydantic | pydantic | 2.9.x | 数据校验 |
| pydantic-settings | pydantic-settings | 2.5.x | 配置管理 |
| requests | requests | 2.32.3 | HTTP 客户端 |
| loguru | loguru | 0.7.3 | 日志 |
| pytest | pytest | 8.3.x | 测试框架 |
| httpx | httpx | 0.27.x | 测试 HTTP 客户端 |

**基础设施版本锁定**：

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 21 (LTS) | Eclipse Temurin / Amazon Corretto |
| Python | 3.12 | CPython |
| MySQL | 8.0+ | Community Server |
| Redis Stack | 7.4+ | 含 RediSearch 模块 |
| Node.js | 18+ (仅 run.js 启动脚本) | 不参与应用构建 |
| Docker | 24+ | 容器化部署 |
| Nginx | 1.24+ | 反向代理 |

**前端依赖版本锁定**：

| 库 | 版本 | 引入方式 | 用途 |
|------|------|----------|------|
| ECharts | 5.5.x | CDN / npm | 图表渲染 |
| marked.js | 12.x | CDN / npm | Markdown 渲染 |
| highlight.js | 11.x | CDN / npm | 代码高亮 |
| Bootstrap | 5.3.x | CDN (ERP) | ERP 管理页面样式 |
| Vue 3 | 3.5.x | npm (Phase 4+) | C 端前端框架（后期） |
| Vite | 5.x | npm (Phase 4+) | 前端构建工具（后期） |

版本锁定清单应随每次依赖升级同步更新，作为团队协作的版本基准。升级依赖前需在测试环境验证，确认无兼容性问题后再合入主分支。
