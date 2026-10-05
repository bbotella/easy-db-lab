## Context

SSH is how easy-db-lab operates, not just how a user logs in. Two SSH clients reach cluster nodes:

- **OpenSSH subprocesses** driven by the workspace `sshConfig`: the SOCKS tunnel
  (`ssh -N -D … -F sshConfig control0`) and every `env.sh` helper (`ssh`, `scp`, `rsync`, `c0`,
  `c-all`, flame graphs), all of which pass `-F "$SSH_CONFIG"`.
- **The in-process Apache MINA SSHD client** (`DefaultSSHConnectionProvider`), behind
  `RemoteOperationsService`: `up`'s readiness wait, instance setup, K3s, helm/kubectl, Tailscale
  bootstrap, uploads and downloads. It dials `host.public:22`.

Both need a TCP path to port 22. On networks that block or mis-route port-22 egress to
unregistered destinations, neither has one.

## Goals / Non-Goals

**Goals:**
- One per-profile switch that moves *both* SSH clients onto SSM Session Manager.
- Zero behaviour change when the switch is `direct` (the default).
- No new commands or aliases — every existing workflow works unchanged under `ssm`.

**Non-Goals:**
- Packer AMI builds over SSM (phase 2: needs a Packer image with `session-manager-plugin`).
- VPC endpoints for SSM — the tool's VPCs always have an internet gateway.
- Removing the port-22 security group rule — harmless under `ssm`, still needed for `direct`.
- A least-privilege replacement for `AmazonSSMManagedInstanceCore` — the role already carries
  broader inline grants, and clusters are throwaway.

## Decisions

### The transport is a profile setting, read at runtime — not snapshotted into `state.json`

`User.sshTransport` (`direct` | `ssm`). Tailscale is snapshotted into state because it changes how
the cluster is *provisioned* (the control node joins a tailnet). SSM does not: the role policy is
always attached and the agent always runs, so a cluster provisioned under either setting is
identical, and the choice is purely about how this machine reaches it. Reading it from the profile
keeps one source of truth. Only `up` writes `sshConfig`, so the transport should be chosen before
`up`; the in-process client follows the profile immediately.

Serialized with Jackson (the existing `User` mechanism) as lowercase `direct` / `ssm`, with one
decoder (`@JsonCreator` on the same `parse` the setup prompt uses), so a hand-edited `SSM` loads.
A profile without the field reads as `direct`.

### One builder for every `aws ssm start-session` command line

`SsmSessionCommand` builds the argv and environment for a target instance and an SSM document,
given the profile's region and credentials:

- `awsProfile` set → `--profile <awsProfile>`; the AWS CLI handles SSO refresh itself.
- static keys → `AWS_SHARED_CREDENTIALS_FILE=<profileDir>/awscredentials` + `--profile default`.
  That file is the one `AWSCredentialsManager` already writes for Packer, so no secret is written
  anywhere new and none appears in `sshConfig`.

Both SSH paths below render from this builder, so they cannot drift.

### One `SshRoute` per transport, decided once

`SshRoute` has one implementation per transport, bound by transport in `SSHModule`, which is the
only place the transport is decided. It answers both questions the two SSH clients ask:
`endpoint(host)` for the in-process client and `proxyCommand(host)` for `sshConfig`.
`DirectSshRoute` returns the public IP and no `ProxyCommand`; `SsmSshRoute` is described below.
`ClusterConfigurationService` asks the route rather than switching on the transport itself.

### OpenSSH path: per-host `ProxyCommand` in `sshConfig`

With `ssm`, each `Host` block gains, after its `Hostname` line:

```
ProxyCommand aws ssm start-session --target <instance-id> --document-name AWS-StartSSHSession --parameters portNumber=%p --region <region> --profile <name>
```

`Hostname` stays immediately after `Host` because `env.sh` reads it with `grep -A 1`. Values are
shell-quoted. The fallback config `env.sh` writes when `sshConfig` is missing carries the same
`ProxyCommand`s. A host with no instance ID fails `sshConfig` generation, before the file is
opened, rather than emitting a broken `ProxyCommand`.

### In-process path: per-instance SSM port forward

MINA SSHD's client supports ProxyJump but not ProxyCommand, so under `ssm` it dials
`SsmSshRoute.endpoint(host)`, a loopback port:

- `SsmSshRoute` runs one `AWS-StartPortForwardingSession` process per instance on an
  OS-assigned local port, waits for the plugin's `Waiting for connections` line, and caches it.
  A cached forward whose process has died is replaced on the next request, so MINA's existing
  stale-session reconnect recovers from an SSM idle timeout.
- A forward that exits or times out before becoming ready throws an `IOException` carrying the
  plugin's output. `up`'s SSH-readiness retry already retries `IOException`, which covers the
  window where a freshly booted instance's agent has not yet registered (`TargetNotConnected`).
- Forward processes, including the `session-manager-plugin` child the AWS CLI spawns, are torn
  down by the route's JVM shutdown hook (nothing calls the SSH provider's `stop()` today; if
  something does, it closes the route too). They are stopped together, against one shared grace
  deadline.
- `Host` gains `instanceId`, populated by `ClusterHost.toHost()`. `SsmSshRoute` is the single
  place a blank instance ID is rejected, for both `endpoint` and `proxyCommand`.

**Alternatives considered:**
- *One SSM forward to control0, ProxyJump to the rest.* Fewer processes, but makes every db-node
  connection depend on control0 and diverges from the per-host `ProxyCommand` model.
- *Route MINA through the SOCKS tunnel + `SocksTcpBridge`.* The tunnel is not up during `up`'s
  readiness wait or instance setup, and does not exist at all when Tailscale is active.
- *Implement the Session Manager WebSocket protocol in the JVM.* Far larger than spawning the
  official plugin.

### `up` preflight for local tooling

With `ssm`, `up` runs `aws --version` and `session-manager-plugin --version` before creating any
AWS resource, mirroring the local Tailscale check, through the same `LocalCliRunner` the
Tailscale check uses. A missing, failing, or hung tool emits `Event.Ssh.SsmToolsMissing` with the
install hint and stops.

### IAM

- **Instance role:** the role's policy set (S3 inline + `AWSPolicy.Managed.SSMManagedInstanceCore`)
  is defined once, in `AWS.attachInstanceRolePolicies`. `createRoleWithS3Policy` uses it for new
  roles, and `up`'s re-apply step reaches it through `AWSResourceSetupService` for existing roles
  (setup returns early when the role already validates). Attaching is idempotent.
- **Operator policy:** `SSMStartSession` and `SSMSessionLifecycle` statements in `iam-policy-ec2.json` allow
  `ssm:StartSession` on account instances and on the `AWS-StartSSHSession` /
  `AWS-StartPortForwardingSession` documents, and `ssm:TerminateSession` / `ssm:ResumeSession`.

## Risks / Trade-offs

- **The proxy may break SSM too** (TLS inspection of the `ssmmessages` WebSocket) → validate
  manually on the affected network before relying on it.
- **Agent registration lag** after boot → covered by `up`'s SSH-readiness retry.
- **Slower tunnel start-up** → over SSM the SOCKS tunnel takes about 6s to become reachable (AWS CLI start-up, StartSession, the plugin's WebSocket, then SSH key exchange and auth). The tunnel verification window was widened from about 5s to about 30s. The loop still returns on first success and bails when ssh dies.
- **Leaked forward processes** if the JVM is killed with SIGKILL → shutdown hook covers normal
  exit and Ctrl-C; SIGKILL is accepted.
- **Plugin output contract** — readiness keys on `Waiting for connections`; a change surfaces as a
  readiness timeout with the plugin's output, not a silent failure.
- **Per-host processes** — one `aws` + plugin pair per node per CLI invocation; acceptable for
  lab-sized clusters.
