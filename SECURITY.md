# Security policy

Agent Browser Cloud has not passed its full V16 production release gates. Local/test mode is a
development environment, not an authentication boundary suitable for the public Internet. Do not
expose it through port forwarding, a public reverse proxy, or a tunnel, and do not use real customer
data in development fixtures. Binding a published port to loopback does not make a public reverse
proxy safe.

## Reporting a vulnerability

Do not include exploit details, credentials, cookies, profiles, screenshots containing personal
data, or production logs in a public issue or pull request.

If GitHub private vulnerability reporting is enabled for this repository, use **Security → Report
a vulnerability**. Otherwise, open a minimal issue requesting a private security contact, without
vulnerability details, and wait for the maintainer to establish a private channel before sharing
evidence. No dedicated security inbox or response-time SLA is currently published.

Provide the affected commit/version, sanitized reproduction steps, expected and actual behavior,
impact, and any mitigation. Test only systems and data you own or are authorized to assess.

## Deployment and support boundaries

- Only explicit `local` and `test` modes accept developer-supplied identity headers. All other
  environment names require the OIDC security chain. Never use those headers as public login.
- Non-local deployments require validated OIDC/RBAC, independent least-privilege worker identities,
  internal mTLS, managed secrets, and the target-environment gates in
  [the remaining-work register](docs/progress/33-当前未实现清单.md).
- Default development signing/encryption keys and database credentials must not be reused outside
  localhost. Changing only the public port, TLS proxy, or frontend login is not sufficient.
- Profile/checkpoint/recording and screenshot data are sensitive. Do not share raw artifacts in bug
  reports. DOM redaction is not proof that image, canvas, or PDF pixels contain no personal data.
- Supported security fixes are tracked on `main`; no historical release support window or
  production security certification is promised. Verify commit-specific CI and deployment evidence.

The reliability/security remediation ledger is maintained in
[progress 165](docs/progress/165-Agent可靠性与个人安全部署修复清单.md).
