# Message Queue Implementation Assessment

**Date:** December 8, 2025  
**Project:** The-Cycle (Hardcore Minecraft Plugin)  
**Purpose:** Evaluate message queue integration for improved stability and performance

---

## Executive Summary

After comprehensive analysis of the project architecture, **a traditional message queue (RabbitMQ, ZeroMQ, etc.) is NOT recommended** for this project. The current dual RPC implementation (BungeeCord plugin messaging + HTTP) is well-suited for the use case and introducing a message queue would add unnecessary complexity without meaningful benefits.

However, several **stability and reliability improvements** have been identified that can be implemented without requiring a message queue.

---

## Current Architecture Analysis

### 1. Communication Patterns

The plugin implements a **distributed architecture** with two server roles:

- **Lobby Server**: Lightweight, always-on server that accepts player commands
- **Hardcore Server**: Backend server that creates/deletes worlds and manages game state

**Current Communication Flow:**
```
Player → Lobby Server → RPC → Hardcore Server → World Cycle → Notify → Lobby Server → Move Players
```

### 2. Existing RPC Implementation

The plugin currently supports **two RPC mechanisms**:

#### A. BungeeCord Plugin Messaging (Primary)
- **Protocol**: BungeeCord "Forward" mechanism via `thecycle:rpc` channel
- **Message Format**: `"rpc::<secret>::<action>::<caller_uuid>"`
- **Authentication**: HMAC-based shared secret validation
- **Advantages**:
  - Native to Minecraft proxy ecosystems (BungeeCord/Velocity)
  - Zero external dependencies
  - Works seamlessly in proxy environments
- **Limitations**:
  - Requires at least one online player to send messages
  - Dependent on proxy infrastructure
  - No built-in retry or persistence

#### B. HTTP RPC (Fallback)
- **Protocol**: HTTP POST to `/rpc` endpoint
- **Server**: Embedded `com.sun.net.httpserver.HttpServer`
- **Authentication**: HMAC signature in `X-Signature` header
- **Advantages**:
  - Works without requiring online players
  - Direct server-to-server communication
  - Independent of proxy infrastructure
- **Limitations**:
  - Synchronous blocking calls (up to 120s timeout)
  - No automatic retry on failure
  - Manual URL configuration required

#### C. Message Queue Integration (Fallback with Retry)
- **Implementation**: `outboundRpcQueue` (ArrayDeque, max 100 messages)
- **Purpose**: Queue messages when BungeeCord channel is unavailable
- **Behavior**: Periodic retry every 20 ticks (1 second)
- **Limitation**: In-memory only, lost on server restart

### 3. Current Stability Mechanisms

**Positive Aspects:**
1. **Dual RPC paths** provide redundancy (plugin messaging + HTTP)
2. **HMAC authentication** prevents unauthorized RPC calls
3. **Graceful degradation** when proxy is unavailable
4. **Player safety**: Waits for players to leave worlds before deletion
5. **Persistent state**: Cycle numbers and pending moves saved to disk
6. **Async operations**: World deletion and webhook calls run asynchronously
7. **Comprehensive error handling** with detailed logging

**Areas for Improvement:**
1. In-memory RPC queue is lost on restart
2. HTTP RPC blocking calls can timeout (120s limit)
3. No dead-letter queue for failed operations
4. No circuit breaker for repeated failures
5. Limited observability into RPC failures

---

## Message Queue Evaluation

### Options Considered

#### 1. **RabbitMQ**
- **Pros**: Enterprise-grade, persistent queues, dead-letter queues, ack/nack support
- **Cons**: Requires separate server process, adds operational complexity, overkill for 2-server setup
- **Verdict**: ❌ Too heavy for this use case

#### 2. **ZeroMQ**
- **Pros**: Lightweight, embedded library, low latency
- **Cons**: No built-in persistence, no broker (peer-to-peer), limited reliability features
- **Verdict**: ❌ Doesn't solve persistence problem

#### 3. **Apache Kafka**
- **Pros**: High throughput, persistent, replay capability
- **Cons**: Extremely heavy, requires Zookeeper/KRaft, designed for big data
- **Verdict**: ❌ Massive overkill

#### 4. **Redis Pub/Sub + Streams**
- **Pros**: Lightweight, persistent streams, built-in retry, widely used
- **Cons**: Requires Redis server, adds dependency
- **Verdict**: ⚠️ Possible but adds complexity for minimal gain

#### 5. **Embedded Options (H2, SQLite)**
- **Pros**: No external dependencies, persistent, simple
- **Cons**: Not a "message queue", but could serve similar purpose
- **Verdict**: ⚠️ Considered for persistent retry queue (see recommendations)

### Why Message Queues Are Not Recommended

1. **Low Message Volume**: The system generates very few messages:
   - World cycles: Minutes to hours apart
   - Player movements: Batched and coordinated
   - Status checks: Infrequent

2. **Simple Communication Pattern**: Point-to-point, request-response
   - No pub/sub requirements
   - No fan-out to multiple consumers
   - No event streaming needs

3. **Operational Overhead**:
   - Requires deploying and maintaining additional infrastructure
   - Adds complexity for small server operators
   - Increases failure points

4. **Existing Solutions Work Well**:
   - Dual RPC paths provide redundancy
   - HMAC authentication provides security
   - Current approach is Minecraft-native

5. **Target Audience**: Small server operators (6-8 players)
   - Adding RabbitMQ/Kafka is unrealistic
   - Simplicity is a feature, not a bug

---

## Recommended Improvements

### Priority 1: Persistent RPC Queue (High Impact)

**Problem**: In-memory RPC queue (`outboundRpcQueue`) is lost on server restart.

**Solution**: Persist failed RPC calls to disk using existing file-based approach.

**Implementation**:
```java
// Similar to pending_moves.json and cycles.json pattern
File: plugins/TheCycle/failed_rpcs.json
Format: [
  {"action": "cycle-now", "caller": "uuid", "timestamp": 1234567890, "attempts": 1},
  ...
]
```

**Benefits**:
- No new dependencies
- Survives server restarts
- Consistent with existing architecture
- Simple to implement (~100 lines)

**Code Changes**:
- New class: `RpcQueueStorage.java` (similar to `PendingMovesStorage.java`)
- Modify: `Main.java` to persist queue on shutdown and load on startup
- Add periodic cleanup of old failed RPCs (e.g., >24 hours)

---

### Priority 2: Exponential Backoff for HTTP RPC (Medium Impact)

**Problem**: HTTP RPC retries with fixed timeout can overwhelm servers during issues.

**Solution**: Implement exponential backoff with jitter for HTTP retry logic.

**Implementation**:
```java
int retryDelay = Math.min(
    (int) (baseDelay * Math.pow(2, attemptNumber) + random.nextInt(jitter)),
    maxDelay
);
```

**Benefits**:
- Reduces load during transient failures
- Improves reliability
- Industry best practice

---

### Priority 3: Health Check Endpoint (Medium Impact)

**Problem**: No easy way to verify RPC communication is working.

**Solution**: Add `/health` endpoint to HTTP RPC server.

**Implementation**:
```java
GET /health → {"status": "ok", "cycleNumber": 42, "role": "hardcore"}
```

**Benefits**:
- Easy monitoring/debugging
- Can verify connectivity before RPC calls
- Supports operational dashboards

---

### Priority 4: Circuit Breaker Pattern (Low Impact)

**Problem**: Repeated failures to same endpoint continue indefinitely.

**Solution**: Implement simple circuit breaker for HTTP RPC.

**States**:
- **Closed**: Normal operation
- **Open**: After N failures, stop trying for M seconds
- **Half-Open**: Allow one test request after cooldown

**Benefits**:
- Prevents cascade failures
- Faster recovery from transient issues
- Better error reporting

---

### Priority 5: Structured Logging for RPC Operations (Low Impact)

**Problem**: Current logging makes it hard to trace RPC flow across servers.

**Solution**: Add correlation IDs and structured log format.

**Implementation**:
```java
LOG.info("RPC_SENT action=cycle-now correlationId=uuid-123 target=hardcore")
LOG.info("RPC_RECEIVED action=cycle-now correlationId=uuid-123 source=lobby")
```

**Benefits**:
- Easier debugging
- Better observability
- Can trace requests across servers

---

### Priority 6: Configuration Validation on Startup (Low Impact)

**Problem**: Misconfiguration may not be detected until runtime.

**Solution**: Validate config on plugin enable.

**Checks**:
- If role=lobby, ensure `server.hardcore` is set
- If HTTP enabled, verify port is valid and not in use
- Validate `rpc_secret` is not empty if HTTP is used
- Check lobby/hardcore URLs are reachable (optional health check)

---

## Additional Stability Recommendations

### 1. Graceful Shutdown Improvements
- Ensure all pending RPCs are persisted before shutdown
- Flush pending moves to disk
- Close HTTP server gracefully with timeout

### 2. Idempotency for World Cycle Operations
- Add generation lock to prevent concurrent cycles
- Check if world already exists before creating
- Make cycle operations idempotent (safe to retry)

### 3. Monitoring and Alerting Hooks
- Add optional metrics export (simple file-based or webhook)
- Track: RPC success/failure rates, cycle durations, player counts
- Alert on repeated failures

### 4. Documentation Updates
- Add troubleshooting section for RPC failures
- Document HTTP fallback setup
- Add network diagram showing communication flow

---

## Implementation Roadmap

### Phase 1: Core Stability (Recommended for immediate implementation)
1. ✅ Create persistent RPC queue storage
2. ✅ Add exponential backoff for HTTP retries
3. ✅ Implement health check endpoint
4. ✅ Add configuration validation

**Estimated Effort**: 4-6 hours  
**Testing**: Unit tests + integration testing with 2 servers

### Phase 2: Enhanced Reliability (Optional, future)
1. Circuit breaker for HTTP RPC
2. Structured logging with correlation IDs
3. Metrics export for monitoring
4. Enhanced idempotency checks

**Estimated Effort**: 6-8 hours  
**Testing**: Integration tests + load testing

### Phase 3: Documentation and Tooling (Optional, future)
1. Updated troubleshooting guide
2. Network architecture diagram
3. Health check dashboard/script
4. Automated deployment scripts

**Estimated Effort**: 3-4 hours

---

## Risk Assessment

### Risks of NOT Implementing Improvements

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Lost RPC messages on restart | High | Medium | Implement persistent queue |
| HTTP timeout during heavy load | Medium | Medium | Add backoff + circuit breaker |
| Misconfiguration causing silent failures | Medium | High | Add validation on startup |
| Difficult debugging of RPC issues | High | Low | Add structured logging |

### Risks of Implementing a Message Queue

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Increased complexity | High | High | Don't do it |
| Operational burden | High | High | Don't do it |
| Additional failure points | High | Medium | Don't do it |
| Learning curve for server ops | High | Medium | Don't do it |

---

## Conclusion

**Key Findings**:
1. ✅ Current RPC implementation is appropriate for use case
2. ✅ Dual mechanism (plugin + HTTP) provides good redundancy
3. ❌ Traditional message queue would add complexity without benefit
4. ✅ Several targeted improvements can enhance stability
5. ✅ File-based persistence is sufficient for this scale

**Final Recommendation**:
- **DO NOT** introduce RabbitMQ, Kafka, ZeroMQ, or similar message queues
- **DO** implement Priority 1 & 2 improvements (persistent queue + backoff)
- **CONSIDER** Priority 3 & 4 for enhanced observability
- **MAINTAIN** current architecture with targeted enhancements

**Rationale**:
This is a small-scale, two-server Minecraft plugin serving 6-8 players. The current architecture is well-designed, uses Minecraft-native patterns, and is appropriate for the scale. The proposed improvements enhance reliability without adding operational complexity or external dependencies.

---

## Appendix A: Code Quality Observations

**Strengths**:
- Clean separation of concerns (services, handlers, listeners)
- Comprehensive unit test coverage (50 tests, all passing)
- Good error handling and logging
- Uses modern Java features (records would be beneficial)
- HMAC authentication for RPC security

**Opportunities**:
- Some methods in `Main.java` are quite long (200+ lines)
- Could benefit from extracting RPC logic to dedicated `RpcService`
- Configuration object could use Java Records
- Consider using `CompletableFuture` for async HTTP calls

---

## Appendix B: Technology Stack Compatibility

**Current Stack**:
- Java 21 (modern LTS)
- Paper API 1.21.10 (Minecraft server API)
- Maven build system
- JUnit 5 + Mockito for testing

**Message Queue Library Compatibility**:
- ✅ RabbitMQ client (Java): Compatible, but requires RabbitMQ server
- ✅ ZeroMQ (JeroMQ): Compatible, pure Java implementation
- ✅ Redis client (Jedis/Lettuce): Compatible, but requires Redis server
- ⚠️ Kafka client: Heavy dependency, not recommended
- ✅ Embedded H2/SQLite: Compatible, no external server needed

---

## References

- Project README: `/README.md`
- Test Coverage: `/TEST_COVERAGE.md`
- Source Code: `/src/main/java/dev/wibbleh/the_cycle/`
- Configuration: `/src/main/resources/config.yml`
