## 1. IAM

- [x] 1.1 Add `AWSPolicy.Managed.SSMManagedInstanceCore` (`arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore`)
- [x] 1.2 Define the instance role's policy set once (`AWS.attachInstanceRolePolicies`: S3 inline + SSM managed) and use it in `createRoleWithS3Policy`
- [x] 1.3 Re-assert it in `up`'s re-apply step (`AccountBucketSetup.reapplyPolicies` → `AWSResourceSetupService.reapplyInstanceRolePolicies`), before instances launch
- [x] 1.4 Add `SSMStartSession` and `SSMSessionLifecycle` statements to `iam-policy-ec2.json` (`ssm:StartSession` on instances + SSH/port-forward documents; `ssm:TerminateSession`, `ssm:ResumeSession`)

## 2. Profile setting

- [x] 2.1 Add `SshTransport` (`direct` | `ssm`, one Jackson decoder shared with the prompt) and `User.sshTransport` defaulting to `direct`
- [x] 2.2 Prompt for the transport in `profile setup` (initial and update modes), re-prompting on an unrecognized value
- [x] 2.3 Show the transport in `profile show`

## 3. SSM session command

- [x] 3.1 `SsmSessionCommand`: argv + environment for `AWS-StartSSHSession` and `AWS-StartPortForwardingSession`, for named-profile and static-key credentials
- [x] 3.2 Koin wiring (`ssmModule`) that derives credentials from `User` (static keys → `AWSCredentialsManager` file), resolved lazily

## 4. OpenSSH path

- [x] 4.1 `ClusterConfigWriter.writeSshConfig` (and the `env.sh` fallback config) emits a per-host `ProxyCommand` after `Hostname`
- [x] 4.2 `ClusterConfigurationService` asks the `SshRoute` for each host's `ProxyCommand`, before the file is opened
- [x] 4.3 Under `ssm`, `sshConfig` sends keepalives (`ServerAliveInterval 30`, `ServerAliveCountMax 3`) so Session Manager's 20-minute idle timeout cannot silently drop the SOCKS tunnel (found when the tunnel died after an hour idle)

## 5. SSH route and in-process MINA path

- [x] 5.1 Add `Host.instanceId`, populated by `ClusterHost.toHost()` and by Packer diagnostics
- [x] 5.2 `SshRoute` (`endpoint` + `proxyCommand`) with `DirectSshRoute`; `DefaultSSHConnectionProvider` dials `route.endpoint` and closes the route on `stop()`
- [x] 5.3 `SsmSshRoute`: per-instance forward, readiness detection, dead-forward replacement, `IOException` on failure, single instance-ID check, teardown on `close()` and JVM shutdown
- [x] 5.4 Koin wiring in `sshModule`: the one place the transport is decided
- [x] 5.5 Widen the SOCKS tunnel verification window from about 5s to about 30s: over SSM the tunnel needs about 6s to come up (found on the first real `up`)

## 6. `up` preflight

- [x] 6.1 `LocalSsmTooling` check for `aws` and `session-manager-plugin`, on the shared `LocalCliRunner` (extracted from the Tailscale check)
- [x] 6.2 `Event.Ssh.SsmToolsMissing` error event carrying each missing tool's install hint
- [x] 6.3 Run the check from `ProvisioningPreflight.verify` (which `up` calls before any AWS resource is created), only when `ssm`

## 7. Tests

- [x] 7.1 `ClusterConfigurationService`: `ProxyCommand` per host under an ssm route (also in the `env.sh` fallback), none under direct, `Hostname` directly after `Host`, missing instance ID fails without writing
- [x] 7.2 `SsmSessionCommand`: argv/env per credential mode and document; shell rendering; lazy credential resolution; `forUser` with a real credentials manager
- [x] 7.3 `SsmSshRoute` against a stub `aws` script: readiness, early exit surfaces output, timeout, reuse, dead-forward replacement, failed start not cached, close terminates the processes, blank instance ID refused
- [x] 7.4 `LocalSsmTooling`: which runner outcomes count as missing; `LocalCliRunner` against real processes
- [x] 7.5 `SetupProfile`: transport prompt saved (initial + update); invalid value re-prompted; blank keeps current
- [x] 7.6 `ProfileShow`: transport displayed
- [x] 7.7 IAM: role creation attaches the SSM managed policy; EC2 user policy grants the SSM session actions
- [x] 7.8 `up`: preflight failure stops before AWS is touched; tools present proceeds; direct never checks
- [x] 7.9 `UserConfigProvider`: profile without the field reads `direct`; `ssm` round-trips lowercase; hand-edited `SSM` loads

## 8. Docs

- [x] 8.1 `docs/user-guide/network-connectivity.md`: SSH over SSM Session Manager section (SSM sits underneath both access methods, so no comparison-table column)
- [x] 8.2 `docs/getting-started/setup.md` and `docs/reference/commands.md`: transport prompt, `profile show`, IAM additions; fix the instance role name
- [x] 8.3 `providers/CLAUDE.md`: SSH transport architecture notes and directory listing
