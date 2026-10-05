# Providers Package

This package contains low-level infrastructure providers for AWS, SSH, and Docker. AWS service classes have been moved to `services/aws/` — this package retains only SDK wrappers, data types, retry utilities, and credentials.

## Directory Structure

```
providers/
├── aws/                    # AWS SDK wrappers and data types
│   ├── AWSModule.kt        # Koin DI registration for all AWS services
│   ├── AWS.kt              # Low-level IAM, S3, STS operations
│   ├── EC2.kt              # Low-level EC2 operations
│   ├── RetryUtil.kt        # Centralized retry configuration
│   ├── PollUntil.kt        # pollUntil: fixed-interval poll over createPollUntilRetryConfig
│   ├── AWSPolicy.kt        # Policy definitions and templates
│   ├── IamPolicy.kt        # IAM policy data model
│   ├── IamPolicySerializers.kt # IAM policy serialization
│   ├── AWSClientFactory.kt # Factory interface for AWS clients
│   ├── AWSCredentialsManager.kt # Credentials management
│   ├── VpcService.kt       # VPC service interface (contract)
│   ├── VpcInfrastructure.kt # VPC infrastructure data types
│   ├── InfrastructureConfig.kt # Infrastructure configuration
│   ├── TeardownTypes.kt    # Data classes: DiscoveredResources, TeardownResult, TeardownMode
│   ├── SecurityGroupService.kt  # Data classes: SecurityGroupDetails, WellKnownPorts
│   ├── AwsTypes.kt         # EC2 type aliases and data classes
│   ├── EMRTypes.kt         # EMR type aliases and data classes
│   ├── InstanceTypes.kt    # Instance type data classes
│   └── model/              # Data models (AMI, etc.)
├── docker/                 # Docker client providers
│   ├── DockerModule.kt
│   ├── DockerClientProvider.kt
│   └── DefaultDockerClientProvider.kt
├── ssh/                    # SSH connection providers
│   ├── SSHModule.kt
│   ├── SSHConnectionProvider.kt
│   ├── DefaultSSHConnectionProvider.kt
│   ├── SshRoute.kt        # How each SSH transport reaches a node (DirectSshRoute)
│   ├── RemoteOperationsService.kt
│   └── DefaultRemoteOperationsService.kt
└── ssm/                    # SSM Session Manager SSH transport
    ├── SsmModule.kt
    ├── SsmSessionCommand.kt # `aws ssm start-session` command lines + credentials
    └── SsmSshRoute.kt       # The `ssm` SshRoute: ProxyCommands + per-instance port forwards
```

## What Belongs Here vs `services/aws/`

**Here (providers/aws/):** Low-level SDK wrappers, interfaces, data types, retry utilities, credentials, policy definitions. Things that directly wrap AWS SDK calls without business logic.

**In services/aws/:** Business-logic services that orchestrate AWS operations, implement service interfaces, and are injected into commands. See [`services/aws/CLAUDE.md`](../../services/aws/CLAUDE.md).

## RetryUtil Factory Methods

**Location:** `providers/aws/RetryUtil.kt`

Always use factory methods instead of creating manual retry configurations.

| Method | Attempts | Backoff | Use Case |
|--------|----------|---------|----------|
| `createIAMRetryConfig()` | 5 | Exponential 1s→16s | IAM operations, handles 404 eventual consistency |
| `createEC2InstanceRetryConfig<T>()` | 5 | Exponential 1s→16s | EC2 instance ops, retries `InvalidInstanceID.NotFound` (matched on the error code, since the message varies with the number of ids) |
| `createAwsRetryConfig<T>()` | 3 | Exponential 1s→4s | S3, EC2, EMR (standard AWS) |
| `createDockerRetryConfig<T>()` | 3 | Exponential 1s→4s | Container start/stop/remove |
| `createNetworkRetryConfig<T>()` | 3 | Exponential 1s→4s | Generic network ops |
| `createSshConnectionRetryConfig()` | 30 | Fixed 10s | SSH boot-up (~5 min total) |
| `createS3LogRetrievalRetryConfig<T>()` | 10 | Fixed 3s | S3 log retrieval (eventual consistency) |
| `createBooleanPollRetryConfig(maxAttempts, interval)` | `maxAttempts` | Fixed `interval` | Retries on result until a boolean check is `true`: the tailnet reachability probe before K3s, and a failed step's `stderr.gz` uploaded after the step ended (28 attempts, 15s apart) |
| `createVpcTeardownRetryConfig<T>()` | 5 | Exponential 5s→40s | VPC teardown DependencyViolation |
| `createEcsRoleRetryConfig()` | 5 | Exponential 1s→16s | ECS calls that pass a role IAM just created (`InvalidParameterException`, `ClientException`); wrapper `withEcsRoleRetry` |
| `createVpcAutoCidrRetryConfig()` | 3 | None | VPC creation on an auto-selected CIDR (`SdkException` only); `up` picks a new random unused block per attempt |
| `createLocalPortBindRetryConfig()` | 3 | Fixed 100ms | Local listener lost its port between selection and bind (`BindException` only); the SOCKS proxy selects a new port per attempt |
| `createPollUntilRetryConfig<T>(maxAttempts, interval, done, deadline?)` | caller | Fixed `interval` | Poll until a result condition holds; any exception is retried within the budget, only the last look's exception fails; an unmet condition returns the last result. An optional wall-clock `deadline` ends the poll however many attempts remain (`waitForRollouts` passes `Int.MAX_VALUE` attempts and a deadline) |

### Convenience Wrappers

The `with*Retry` wrappers are top-level functions in `RetryWrappers.kt`, beside `RetryUtil`, not
members of it; `RetryUtil` holds only the config factories.

```kotlin
// Short-hand for common patterns (top-level functions in RetryWrappers.kt)
val result = withAwsRetry("describe-cluster") { emrClient.describeCluster(request) }
val result = withEc2InstanceRetry("describe") { ec2Client.describeInstances(request) }
withVpcTeardownRetry("delete-sg") { ec2Client.deleteSecurityGroup(request) }
withS3BucketPolicyRetry("put-bucket-policy") { s3Client.putBucketPolicy(request) }

// Poll until a condition holds (top-level pollUntil in PollUntil.kt, over createPollUntilRetryConfig);
// returns the last result if it never does
val pods = pollUntil("wait-for-pod", maxAttempts = 10, interval = Duration.ofSeconds(3), done = { it.isNotEmpty() }) {
    k8sService.getPods().getOrThrow()
}
```

## AWSModule.kt Registration

`AWSModule.kt` remains here because it registers both low-level SDK clients and service-layer singletons via Koin. Services from `services/aws/` are imported and registered here alongside their SDK client dependencies.

## SSH Provider

- `SSHConnectionProvider` — manages connection pool, auto-reconnects, keepalive
- `RemoteOperationsService` — high-level SSH ops (execute, upload, download)
- Registered in `SSHModule.kt`: provider as **singleton**, remote ops as **factory**

### SSH Transport (`direct` / `ssm`)

The profile's `User.sshTransport` decides how a TCP connection to a node's port 22 is made; SSH
itself is the same either way. It is read from the profile at runtime, never snapshotted into
`state.json`, because clusters are provisioned identically under both transports.

The transport is decided in exactly one place: the `SshRoute` binding in `SSHModule.kt`
(`DirectSshRoute` or `ssm/SsmSshRoute`). Everything else asks the route; do not add another
`when (sshTransport)` elsewhere. The only other reader is `up`'s local-tooling preflight.

- **OpenSSH paths** (the SOCKS tunnel, every `env.sh` helper) use `sshConfig`.
  `ClusterConfigurationService` writes `route.proxyCommand(host)` into each `Host` block (and
  into the fallback config `env.sh` writes when `sshConfig` is missing). Under `ssm` that is
  `aws ssm start-session --document-name AWS-StartSSHSession`. The `Hostname` line must stay
  directly after `Host`, because `env.sh` reads it with `grep -A 1`.
  Under `ssm` the config also carries global `ServerAliveInterval`/`ServerAliveCountMax`, because
  Session Manager drops a session after 20 idle minutes and a quiet SOCKS tunnel would otherwise die.
- **The in-process MINA client** cannot run a `ProxyCommand`. `DefaultSSHConnectionProvider`
  dials `route.endpoint(host)`: the public IP under `direct`, or under `ssm` a loopback port
  forwarded by an `AWS-StartPortForwardingSession` process (one per instance per JVM).
- Both paths build their command lines with `ssm/SsmSessionCommandBuilder`, so region and
  credentials cannot drift. Static keys reach the AWS CLI through the profile's
  `AWSCredentialsManager` file (`AWS_SHARED_CREDENTIALS_FILE`), never as environment variables
  written into `sshConfig`.
- A forward that fails to start throws `IOException`, which `up`'s SSH-readiness retry already
  retries. That retry covers the window before a fresh node's SSM agent registers.
- Forward processes, including the `session-manager-plugin` child, are stopped by `SsmSshRoute`'s
  JVM shutdown hook. Nothing calls `SSHConnectionProvider.stop()` in production today; if
  something starts to, it closes the route too.
- `Host.instanceId` is required under `ssm`. Any new place that builds a `Host` must populate it
  (`ClusterHost.toHost()` does).

### Credential Redaction Is A Boundary, Not A Call-Site Convention

Remote commands legitimately carry a URL with an embedded credential — a git clone of a private
fork is the common case — and the remote side echoes the command straight back on failure.
`ssh/SSHClient` therefore redacts at the single point every remote command passes through, covering
all four sinks at once: the debug log, the emitted `Event.Ssh.CommandOutput`, the returned
`Response`, and the thrown `RemoteCommandFailedException`. The helper is
`ssh/Redaction.kt`'s `redactUrlCredentials`.

Rules that follow from this:

- **Do not redact per call site.** A convention only has to be forgotten once to leak. If a new
  sink appears (a new exception type, a new event), redact it inside `SSHClient` too.
- **Do not lower `org.apache.sshd` below `INFO`** in `easydblab-logback.xml`. MINA logs every
  remote command verbatim at DEBUG, one layer *below* this boundary, straight into `debug.log` and
  its 30 days of archives. `LogbackConfigurationTest` locks this in.
- `executeRemotely(secret = true)` is stronger still: neither the command nor any of its output is
  repeated anywhere, because a failing secret command routinely echoes its own argument back.

## Docker Provider

- `DockerClientProvider` — lazy-initialized Docker client (expensive to create)
- Registered in `DockerModule.kt` as **singleton**
- `Docker` instances created as **factory** (stateful, tied to context)
