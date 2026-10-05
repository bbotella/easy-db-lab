## ADDED Requirements

### Requirement: Instance role supports SSM Session Manager

The cluster instance role (`EasyDBLabEC2Role`) MUST carry the AWS managed
`AmazonSSMManagedInstanceCore` policy, regardless of the profile's SSH transport, so that any
cluster can be reached over SSM Session Manager.

#### Scenario: New role receives the SSM policy

- **GIVEN** a profile whose instance role does not yet exist
- **WHEN** setup creates the role
- **THEN** the `AmazonSSMManagedInstanceCore` managed policy is attached to it

#### Scenario: Existing role receives the SSM policy on up

- **GIVEN** an instance role created before this policy was required
- **WHEN** the user runs `up`
- **THEN** the `AmazonSSMManagedInstanceCore` managed policy is attached before instances are launched
- **AND** attaching it again when already attached succeeds

### Requirement: Profile setup offers the SSH transport

The profile setup workflow MUST let the user choose the SSH transport (`direct` or `ssm`), default
`direct`, and the profile display MUST show the configured transport.

#### Scenario: User selects SSM during setup

- **GIVEN** a user running profile setup
- **WHEN** they answer `ssm` to the SSH transport prompt
- **THEN** the profile's SSH transport is saved as `ssm`

#### Scenario: Unrecognized transport is rejected

- **GIVEN** a user running profile setup
- **WHEN** they answer the SSH transport prompt with a value other than `direct` or `ssm`
- **THEN** the value is rejected and the user is asked again

#### Scenario: Profile display shows the transport

- **GIVEN** a configured profile
- **WHEN** the user displays the profile
- **THEN** the SSH transport is shown

## MODIFIED Requirements

### Requirement: IAM Policy Visibility

The system MUST allow users to view the IAM policies required for operation. The displayed
policies MUST include the permissions needed to open SSM Session Manager sessions to cluster
instances.

#### Scenario: User views required IAM policies

- **GIVEN** a user troubleshooting permissions
- **WHEN** they request IAM policy display
- **THEN** the required policies are shown with account-specific values substituted.

#### Scenario: Displayed policies cover SSM sessions

- **GIVEN** a user requesting IAM policies from an administrator
- **WHEN** they display the required policies
- **THEN** the policies grant starting SSM sessions to the account's instances using the SSH and port-forwarding session documents, and terminating and resuming those sessions
