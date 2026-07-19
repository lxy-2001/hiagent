# 日志与异常处理说明

## 1. 日志框架

### 1.1 当前状态

| 项目 | 状态 | 说明 |
|------|------|------|
| 日志框架 | Spring Boot 默认 | Logback（Spring Boot 内置） |
| 日志配置文件 | 无 | 使用 Spring Boot 默认配置 |
| 自定义日志级别 | 无 | 使用默认 INFO 级别 |
| 日志输出格式 | 默认 | 控制台输出 |

**代码依据**：未发现 `logback-spring.xml` 或 `log4j2.xml`

### 1.2 日志使用情况

当前代码中日志使用较少：

| 类 | 日志使用 | 说明 |
|-----|---------|------|
| `QdrantClient` | `LoggerFactory.getLogger()` | 记录 Qdrant 操作 |
| 其他类 | 无 | 未使用日志 |

**代码依据**：`QdrantClient.java`

### 1.3 建议的日志配置

创建 `agent-demo/src/main/resources/logback-spring.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="APP_NAME" source="spring.application.name"/>

    <!-- 控制台输出 -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 文件输出 -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/${APP_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/${APP_NAME}.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 应用日志 -->
    <logger name="com.agentflow" level="DEBUG"/>

    <!-- Spring Security 日志 -->
    <logger name="org.springframework.security" level="INFO"/>

    <!-- SQL 日志 -->
    <logger name="org.hibernate.SQL" level="DEBUG"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

## 2. 异常处理

### 2.1 当前状态

| 项目 | 状态 | 说明 |
|------|------|------|
| 全局异常处理器 | 无 | 未实现 `@ControllerAdvice` |
| 自定义异常类 | 无 | 使用 Spring 内置异常 |
| 异常响应格式 | 不统一 | Spring Boot 默认格式 |

**代码依据**：未发现 `@ControllerAdvice` 或自定义异常类

### 2.2 异常使用方式

当前代码直接抛出 Spring 的 `ResponseStatusException`：

```java
// 认证失败
throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");

// 资源不存在
throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
```

**代码依据**：
- `AuthService.java:47,49,58,62,66`
- `AgentTaskService.java:53`

### 2.3 SSE 异常处理

SSE 流中的异常处理：

**ChatController**：

```java
private void streamInBackground(ChatRequest request, SseEmitter emitter) {
    try {
        ChatResponse response = chatService.stream(request, delta -> sendDelta(emitter, delta));
        emitter.send(SseEmitter.event().name("done").data(response));
        emitter.complete();
    } catch (Exception ex) {
        sendError(emitter, ex);
    }
}

private void sendError(SseEmitter emitter, Exception ex) {
    try {
        String message = ex.getMessage() == null ? "Chat stream failed" : ex.getMessage();
        emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
        emitter.complete();
    } catch (IOException sendError) {
        emitter.completeWithError(sendError);
    }
}
```

**TaskEventPublisher**：

```java
public void error(String taskId, Throwable throwable) {
    for (SseEmitter emitter : emitters.getOrDefault(taskId, new CopyOnWriteArrayList<>())) {
        emitter.completeWithError(throwable);
    }
    emitters.remove(taskId);
}
```

**代码依据**：
- `ChatController.java:56-66`
- `TaskEventPublisher.java:69-74`

### 2.4 Agent 执行异常处理

```java
// AgentTaskService.java
private void execute(String taskId) {
    try {
        AgentTaskEntity task = taskRepository.findById(taskId).orElseThrow();
        AgentResult result = agentRuntime.run(
            new AgentRequest(task.getId(), task.getSessionId(),
                           task.getUserId(), task.getUserInput()),
            eventPublisher::publish);
        task.complete(result.finalAnswer());
        taskRepository.save(task);
        eventPublisher.complete(taskId);
    } catch (RuntimeException ex) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.fail(ex.getMessage());
            taskRepository.save(task);
        });
        eventPublisher.error(taskId, ex);
    }
}
```

**代码依据**：`AgentTaskService.java:69-83`

## 3. 建议的异常处理方案

### 3.1 自定义异常类

```java
// 建议创建
public class AgentFlowException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AgentFlowException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}

public class AuthenticationException extends AgentFlowException {
    public AuthenticationException(String message) {
        super(HttpStatus.UNAUTHORIZED, "AUTH_FAILED", message);
    }
}

public class ResourceNotFoundException extends AgentFlowException {
    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}

public class ToolExecutionException extends AgentFlowException {
    public ToolExecutionException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "TOOL_ERROR", message);
    }
}
```

### 3.2 全局异常处理器

```java
// 建议创建
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentFlowException.class)
    public ResponseEntity<ErrorResponse> handleAgentFlowException(AgentFlowException ex) {
        log.warn("Business exception: {} - {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
            .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
            .body(new ErrorResponse(
                String.valueOf(ex.getStatusCode().value()),
                ex.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("INTERNAL_ERROR", "服务器内部错误"));
    }

    public record ErrorResponse(String code, String message) {}
}
```

## 4. 错误响应格式

### 4.1 当前格式（Spring Boot 默认）

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "用户名或密码错误",
  "path": "/api/auth/login"
}
```

### 4.2 建议的统一格式

```json
{
  "code": "AUTH_FAILED",
  "message": "用户名或密码错误",
  "timestamp": "2024-01-01T12:00:00Z",
  "path": "/api/auth/login"
}
```

## 5. 监控与告警

### 5.1 当前状态

| 项目 | 状态 |
|------|------|
| 健康检查 | 待确认（需 actuator） |
| 指标收集 | 待确认（需 micrometer） |
| 告警通知 | 待确认 |

### 5.2 建议集成

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  endpoint:
    health:
      show-details: always
```

## 6. 待确认项

| # | 项目 | 状态 | 说明 |
|---|------|------|------|
| 1 | 全局异常处理器 | 待确认 | 是否需要 @ControllerAdvice？ |
| 2 | 自定义异常类 | 待确认 | 是否需要统一异常体系？ |
| 3 | 日志配置文件 | 待确认 | 是否需要 logback-spring.xml？ |
| 4 | 日志收集 | 待确认 | 是否需要 ELK/Loki？ |
| 5 | 健康检查 | 待确认 | 是否需要 Actuator？ |
| 6 | 告警通知 | 待确认 | 是否需要钉钉/飞书告警？ |
