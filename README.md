# 5G Technology — SBA Stack

**Build reliable 5G Service-Based Architecture applications without rebuilding the telecom foundation.**

5G SBA Stack is a modular Java library for developing 3GPP-aligned network functions and services. It provides reusable components for HTTP/2 communication, NRF discovery, security, SMS TPDU encoding and decoding, generated 3GPP API models, and integration testing.

## Key capabilities

- 5G SBA communication over HTTP/2 with Netty
- NF registration, discovery, heartbeat, failover, and load balancing through NRF
- SMS TPDU encoding and decoding with GSM-7, UCS-2, and DCS support
- TLS/mTLS, JWT, OAuth 2.0, API key authentication, and rate limiting
- Reusable API contracts, DTOs, validation, exceptions, and utilities
- Test fixtures and mock SBA services for NRF, UDM, AMF, SMSF, and OAuth2/JWK flows

## Modules

| Module | Purpose |
| --- | --- |
| `sba-stack-core` | Shared contracts, generated models, DTOs, validation, and utilities |
| `sba-stack-http2` | HTTP/2 client and transport components |
| `sba-stack-discovery` | NRF registration and NF discovery |
| `sba-stack-security` | Authentication, authorization, TLS/mTLS, and traffic protection |
| `sba-stack-codec` | SMS TPDU encoding and decoding |
| `sba-stack-testing` | Mock services, fixtures, simulators, and end-to-end test helpers |

## Requirements

- Java 21
- Apache Maven 3.9 or later

## Install locally

Clone the repository and install every module in your local Maven repository:

```bash
git clone <repository-url>
cd 5g-sba-stack
mvn clean install
```

The artifacts will be available locally with version `1.0.0-SNAPSHOT`.

## Use in your project

Add only the modules your application needs. For example, to use NF discovery:

```xml
<dependency>
    <groupId>com.paicore.sba.stack</groupId>
    <artifactId>sba-stack-discovery</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Replace `sba-stack-discovery` with `sba-stack-http2`, `sba-stack-security`, `sba-stack-codec`, `sba-stack-core`, or `sba-stack-testing` as required.

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE.txt).
