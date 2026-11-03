# Security policy

Chronicle handles research participant data.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on the affected repository
(**Security → Report a vulnerability**). It reaches the maintainers without creating a
public issue. Include the commit (`git rev-parse --short HEAD`), the component, steps to reproduce, and
the impact.

Do not open a public issue or pull request for a security problem, and do not test against
deployments you do not operate.

## What to expect

- Acknowledgement of the report, and a first assessment of severity.
- A fix on the default branch, with credit in the changelog unless you prefer to stay anonymous.
- Coordinated disclosure: we ask that you hold details until the fix has shipped.

## Supported versions

Only the tip of each repository's default branch receives security fixes.

## Transport security

The app requires HTTPS and trusts the system certificate store. It does not pin
certificates or public keys: each self-host deployment uses its own domain and
certificate authority, and Let's Encrypt certificates rotate every 90 days, so a pin
shipped in the app would break every deployment it did not name. After enrollment each
device authenticates with its own API key.
