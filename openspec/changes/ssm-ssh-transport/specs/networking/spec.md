## ADDED Requirements

### Requirement: SSH transport over SSM Session Manager

The system MUST support carrying every SSH connection to cluster nodes over AWS Systems Manager
Session Manager instead of a direct TCP connection to the node's public IP, selected by the user
profile's SSH transport setting (`direct` or `ssm`, default `direct`).

When the transport is `ssm`, both the generated SSH configuration (used by the SOCKS proxy and by
the shell helpers in `env.sh`) and the CLI's in-process SSH connections SHALL reach each node
through an SSM session targeting that node's instance ID. No inbound security group rule from the
operator SHALL be required for these connections to succeed.

When the transport is `direct`, SSH behaviour SHALL be unchanged.

The SSM sessions SHALL authenticate with the same AWS identity the profile is configured with: the
named AWS profile when one is set, otherwise the profile's static credentials. Static credentials
SHALL NOT be written into the generated SSH configuration.

#### Scenario: Generated SSH configuration routes through SSM

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the cluster's SSH configuration is generated
- **THEN** every host entry routes its connection through an SSM SSH session targeting that host's instance ID in the profile's region
- **AND** each host entry's `Hostname` line still immediately follows its `Host` line

#### Scenario: Direct transport leaves the SSH configuration unchanged

- **GIVEN** a profile whose SSH transport is `direct` or unset
- **WHEN** the cluster's SSH configuration is generated
- **THEN** no host entry routes through SSM

#### Scenario: Idle SSM connections are kept alive

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** a long-lived SSH connection such as the SOCKS proxy carries no traffic for longer than Session Manager's idle timeout
- **THEN** the connection stays usable, because the generated SSH configuration sends keepalives

#### Scenario: Shell helpers work unchanged over SSM

- **GIVEN** a provisioned cluster and a profile whose SSH transport is `ssm`
- **WHEN** the user runs `ssh db0`, a `c0` alias, or starts the SOCKS proxy after `source env.sh`
- **THEN** the connection is established over SSM with no change to the command

#### Scenario: In-process SSH connections use an SSM port forward

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the CLI opens an SSH connection to a cluster node
- **THEN** it connects through a local SSM port-forwarding session to that node's port 22, not to the node's public IP

#### Scenario: Port forward that cannot start is retried while the node boots

- **GIVEN** a freshly launched node whose SSM agent has not yet registered
- **WHEN** `up` waits for SSH readiness over SSM
- **THEN** the failed session is treated like any other not-yet-ready SSH connection and retried
- **AND** a session that never becomes ready surfaces the Session Manager plugin's own output in the error

#### Scenario: Missing instance ID fails fast

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** an SSH connection is requested for a host with no recorded instance ID
- **THEN** the operation fails with an error naming the host, rather than attempting a direct connection

#### Scenario: Local SSM tooling is verified before provisioning

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the user runs `up` on a machine missing the AWS CLI or the Session Manager plugin
- **THEN** `up` stops before creating any AWS resource
- **AND** the error names the missing tool and how to install it

#### Scenario: Port forwards do not outlive the CLI

- **GIVEN** SSM port-forwarding sessions started by a CLI invocation
- **WHEN** the invocation exits normally or is interrupted
- **THEN** the forwarding processes it started are terminated
