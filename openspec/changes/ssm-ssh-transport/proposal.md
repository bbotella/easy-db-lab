## Why

Every interaction easy-db-lab has with cluster nodes travels over SSH to the node's public IP on
port 22: `up`'s readiness wait, all remote operations, the SOCKS tunnel, Tailscale bootstrap, and
the `env.sh` aliases. On networks that route egress through a corporate security proxy performing
source-IP anchoring (e.g. Zscaler), port-22 connections to freshly provisioned, never
pre-registered public IPs time out, so `up` cannot complete and the tool has no working
connection method at all.

AWS Systems Manager Session Manager reaches an instance through an outbound connection the SSM
agent makes to AWS, so it needs no inbound port and no knowledge of the operator's source IP.
Carrying SSH over Session Manager removes the hard dependency on inbound port-22 reachability
without changing anything else about how the tool works.

## What Changes

- A per-profile **SSH transport** setting: `direct` (default, today's behaviour) or `ssm`.
  `profile setup` offers it; `profile show` displays it.
- With `ssm`:
  - The generated `sshConfig` routes each host through an SSM session (`ProxyCommand`), so the
    SOCKS tunnel and every `env.sh` alias work unchanged.
  - The in-process SSH client reaches each node through a local SSM port-forwarding session.
  - `up` verifies the AWS CLI and the Session Manager plugin are installed locally before it
    creates any AWS resource.
- The cluster instance role (`EasyDBLabEC2Role`) always carries the AWS managed
  `AmazonSSMManagedInstanceCore` policy — attached when the role is created and re-asserted on
  every `up`, so existing profiles pick it up without re-running setup.
- The operator IAM policy shown by `show-iam-policies` includes the SSM session permissions.
- Packer AMI builds over SSM are **out of scope** (phase 2).

## Capabilities

### New Capabilities

None — the change extends existing capabilities.

### Modified Capabilities

- `networking`: SSH connections may be carried over SSM Session Manager, selected per profile.
- `setup`: the instance role carries SSM permissions; the operator policy includes SSM session
  permissions; profile setup offers the SSH transport.

## Impact

- User profile (`settings.yaml`): new `sshTransport` field, default `direct`.
- Generated `sshConfig`: per-host `ProxyCommand` when `ssm`.
- In-process SSH client: endpoint resolution becomes transport-aware; SSM port-forward processes
  are started per instance and torn down at JVM exit.
- `up`: local tooling preflight when `ssm`; instance role SSM policy re-asserted.
- IAM: managed policy on `EasyDBLabEC2Role`; SSM statement in the EC2 user policy.
- New local prerequisites when `ssm`: AWS CLI v2 and `session-manager-plugin`.
- Docs: network connectivity guide, setup guide.
