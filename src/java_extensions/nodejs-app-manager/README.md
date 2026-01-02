# NodeJS Application Manager - NiFi Controller Service

A NiFi Controller Service for managing Node.js application lifecycles from within the NiFi environment.

## Overview

**NodeJSApplicationManagerService** enables NiFi to start, stop, monitor, and restart Node.js processes, providing centralized management of frontend applications or Node.js-based services alongside data processing flows.

**Version:** 1.0.0-SNAPSHOT
**Status:** Production Ready
**Pattern:** NiFi Pattern B (Controller Service)

## Key Features

- ✅ **Process Lifecycle Management** - Start, stop, and restart Node.js applications
- ✅ **Health Monitoring** - HTTP-based health checks with auto-restart capabilities
- ✅ **Multi-Package Manager Support** - Auto-detects npm, yarn, pnpm, or bun
- ✅ **Log Capture** - Captures and buffers application stdout/stderr
- ✅ **Environment Configuration** - Flexible environment variable injection
- ✅ **Metrics Tracking** - Health check statistics and success rates

## Documentation

- **[SPECIFICATION.md](SPECIFICATION.md)** - Complete technical specification including:
  - Architecture and design
  - API reference
  - Configuration properties
  - Deployment guide
  - Security considerations
  - Testing requirements

- **[BACKLOG.md](BACKLOG.md)** - Development backlog with:
  - Remaining enhancement tasks
  - Completed work summary
  - Implementation priorities

## Quick Start

### Prerequisites

- Apache NiFi 2.6.0+
- Java 21
- Node.js 18+

### Build

```bash
cd liquid-library/src/java_extensions/nodejs-app-manager
mvn clean install
```

NAR files will be created in:
- `nodejs-app-manager-service-api-nar/target/nodejs-app-manager-service-api-nar-1.0.0-SNAPSHOT.nar`
- `nodejs-app-manager-service-nar/target/nodejs-app-manager-service-nar-1.0.0-SNAPSHOT.nar`

### Installation

Copy both NAR files to NiFi's `lib/` directory and restart NiFi.

### Configuration Example

```properties
Application Directory: /opt/nifi/apps/my-app
Package Manager: auto-detect
Start Command: start
Application Port: 3000
Health Check Interval: 30 sec
Auto-Restart on Crash: true
Max Restart Attempts: 3
```

## Module Structure

```
nodejs-app-manager/
├── README.md                              # This file
├── SPECIFICATION.md                       # Technical specification
├── BACKLOG.md                            # Development backlog
├── pom.xml                               # Parent POM
│
├── nodejs-app-manager-service-api/       # API Module (JAR)
│   └── src/main/java/.../
│       ├── NodeJSApplicationManagerService.java
│       ├── ProcessStatus.java
│       └── ProcessManagementException.java
│
├── nodejs-app-manager-service-api-nar/   # API NAR
│
├── nodejs-app-manager-service/           # Implementation Module (JAR)
│   └── src/main/java/.../
│       ├── StandardNodeJSApplicationManagerService.java
│       ├── ProcessLifecycleManager.java
│       ├── ProcessMonitor.java
│       └── LogCapture.java
│
└── nodejs-app-manager-service-nar/       # Implementation NAR
```

## API Overview

### Interface: NodeJSApplicationManagerService

```java
boolean isApplicationRunning()
ProcessStatus getApplicationStatus()
List<String> getApplicationLogs(int maxLines)
void restartApplication() throws ProcessManagementException
int getApplicationPort()
String getApplicationUrl()
```

### Process States

- `STOPPED` - Process not running
- `STARTING` - Process launching
- `RUNNING` - Process running and healthy
- `UNHEALTHY` - Process running but health checks failing
- `STOPPING` - Process shutting down
- `CRASHED` - Process terminated unexpectedly

## Use Cases

1. **Frontend Application Management**
   - Host Next.js, React, or Vue applications within NiFi
   - Provide web-based UIs for quality systems or dashboards

2. **Microservice Coordination**
   - Manage Node.js REST APIs used by NiFi flows
   - Coordinate event-driven architectures

3. **Development Environments**
   - Run development servers with hot-reload
   - Local testing of integrated solutions

## Development Status

**Current Version:** 1.0.0-SNAPSHOT
**Completion:** 15 of 22 issues resolved (68%)
**Production Ready:** Yes

All HIGH priority issues resolved. Remaining issues are enhancements that don't impact core functionality.

See [BACKLOG.md](BACKLOG.md) for details on completed work and future tasks.

## Git Repository

**Branch:** `feat/nodejs-app-manager-v1.0.0-controller-service`
**Repository:** liquid-library

### Recent Commits

1. `39c7d45` - Initial comprehensive refactoring (10 issues)
2. `11c954f` - Additional code quality improvements (3 issues)
3. `37e05e6` - Metrics tracking and method naming review (2 issues)

## Support

For questions about this controller service:
1. Review the [SPECIFICATION.md](SPECIFICATION.md) for technical details
2. Check the [BACKLOG.md](BACKLOG.md) for known issues and future work
3. Contact the development team

## License

Licensed under the Apache License, Version 2.0. See LICENSE file for details.

---

*For complete technical documentation, see [SPECIFICATION.md](SPECIFICATION.md)*