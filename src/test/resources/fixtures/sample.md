# Service Level Agreement — Acme Cloud Platform

## 1. Availability commitment

Acme Cloud commits to a monthly uptime percentage of 99.95% for the compute and
storage tiers. Uptime is measured across five-minute intervals from three
independent probe regions. An interval counts as downtime when more than half of
the probes fail to receive a successful response from the customer's primary
endpoint. Scheduled maintenance announced at least seventy-two hours in advance
is excluded from the calculation, capped at four hours per calendar month.

## 2. Service credits

When the monthly uptime percentage falls below the commitment, customers are
entitled to service credits applied to the following invoice. The credit
schedule is graduated: 10% of the monthly fee for uptime between 99.0% and
99.95%, 25% for uptime between 95.0% and 99.0%, and 50% for uptime below 95.0%.
Credits are the sole and exclusive remedy for availability failures and never
exceed the fees paid for the affected billing period. Claims must be submitted
within thirty days of the incident with request logs demonstrating the failures.

## 3. Termination clause

Either party may terminate the agreement with thirty days written notice if the
other party materially breaches the agreement and fails to cure the breach
within the notice period. Additionally, the customer may terminate immediately,
without penalty, if the monthly uptime percentage falls below 95.0% in two
consecutive calendar months. Upon termination, Acme Cloud provides a
machine-readable export of all customer data and retains backups for ninety
days, after which all customer data is permanently deleted from primary and
archival systems.

## 4. Support tiers and response times

Standard support answers tickets within eight business hours. Premium support
guarantees a fifteen-minute first response for severity-one incidents,
one hour for severity two, and four hours for severity three. Severity one means
a production outage with no workaround; severity two means degraded performance
with a partial workaround; severity three covers general questions and cosmetic
issues. Premium customers also receive a named technical account manager and
quarterly architecture reviews.

## 5. Data protection and processing

Acme Cloud processes customer data solely on documented instructions, encrypts
data in transit with TLS 1.3 and at rest with AES-256, and maintains SOC 2
Type II and ISO 27001 certifications, renewed annually. Sub-processors are
listed publicly and changes are announced thirty days in advance, giving
customers the right to object. Regional data residency can be pinned to the EU,
US, or APAC storage clusters, and cross-region replication can be disabled for
regulated workloads.
