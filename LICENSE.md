# CAT Commercial License

Version 1.0

## Hi! Here's what you need to know:

| Tier | Who it's for |
|------|--------------|
| **Core** | Free edition —core workflow, single user |
| **Pro** | Professional developers and teams —adds advanced features and collaboration |
| **Enterprise** | Organizations 50+ —adds compliance & integrations |

**Core is free** - Core provides the essential AI coding workflow: plan-work-commit-merge, worktree isolation, git
safety, and basic hooks. No time limits.

**Pro unlocks advanced features and collaboration** - When you need stakeholder reviews, RCA, task decomposition,
research, token tracking, or any team collaboration features (task locking, shared config, team analytics,
cross-session handoff), upgrade to Pro. Any user may purchase Pro for a single seat.

**Enterprise adds compliance** - SSO/SAML, audit logs, issue tracker sync, Slack integration, webhook APIs, SLA
support.

**Billing basics:**
- Pro: monthly or annual (annual = 2 months free)
- Enterprise: annual only
- Cancel anytime, 30 days before renewal
- If payment fails, you have 14 days to fix it before downgrade to Core
- Working offline? 30-day grace period so CI/CD and air-gapped setups work fine

**Building a product with CAT?**
- If your product runs CAT for your users (like a code formatting SaaS), each user needs their own license.
- If you're just using CAT internally to keep your own codebase tidy, your customers don't need licenses - only your
  team does.

**About forks** - If someone forks CAT, commercial users still need to get their license from me (the original author).
This keeps things fair and prevents someone from just repackaging the project.

**Don't be shady** - No tampering with license keys, no sharing keys between companies, no bypassing the license check,
no lying about seat counts.

**The fine print** - No warranty, and if you break the rules you lose your license. The legal details are below.

Questions? Reach out through the project repository.

---

## Legal Terms

The following sections contain the legally binding license terms.

## Acceptance

To use, copy, modify, or distribute this software, you must accept these license terms.

## Definitions

**"Software"** means the CAT software and all associated source code, documentation, and related files in this
repository.

**"Licensor"** means the original author and copyright holder of the Software.

**"You"** means the individual or entity exercising rights under this license.

**"Personal Use"** means use by an individual for personal, educational, research, experimental, or hobby purposes,
where the use is not intended for or directed toward commercial advantage or monetary compensation.

**"Commercial Use"** means any use of the Software that is primarily intended for or directed toward commercial
advantage or monetary compensation. This includes, but is not limited to:
- Use by a for-profit company or organization
- Use by employees in the course of their employment (covered under employer's license)
- Use by contractors performing work for a licensed organization (covered under that organization's license while
  performing such work)
- Use in developing, testing, or deploying commercial products or services
- Use in providing paid consulting or professional services (requires consultant's own license unless client provides
  access under their license)

**"Core Use"** means use of Core Features by a single individual user, regardless of whether that use is personal or
commercial. Core Use is free and does not require a paid license, but is limited to a single user seat.

**"Core Features"** means the basic workflow functionality including: plan-work-commit-merge task orchestration,
worktree isolation, basic commit hooks, git safety tools, and status and init commands.

**"Pro Features"** means advanced productivity and collaboration functionality including but not limited to:
stakeholder reviews, RCA, task decomposition, multi-stakeholder research, token usage tracking, single-agent and
multi-agent execution, parallel task execution, all commands and skills access, collision prevention, team pulse
dashboard, shared brain conventions, context preservation, branch policies, team analytics, project budgets,
Slack/Discord notifications, GitHub PR integration, CI status awareness, GitHub Issues sync, and Linear sync.

**"Enterprise Features"** means compliance and integration functionality including but not limited to: SSO/SAML
authentication, SCIM provisioning, audit log export, compliance data export, human review gates, issue tracker
synchronization (Jira), messaging platform notifications (Microsoft Teams), webhook APIs, data residency options,
priority support, and SLA guarantees.

**"Derivative Work"** means any work that is based on or derived from the Software, including modifications,
translations, adaptations, and works that incorporate substantial portions of the Software.

## Grant of Rights for Core Use

Subject to the terms of this license, the Licensor grants You a worldwide, royalty-free, non-exclusive,
non-transferable license to:

1. Use the Software for Core Use (single user, personal or commercial)
2. Copy and distribute the Software
3. Modify the Software and create Derivative Works
4. Distribute Derivative Works

**Core Use is free** —A single individual may use CAT Core Features for any purpose, including commercial work,
without purchasing a license. This grant covers Core Features only and excludes Pro Features and Enterprise Features.

## Pro and Enterprise Features Require a Paid License

**Access to Pro Features requires a Pro license.**

**Access to Enterprise Features requires an Enterprise license.**

**Payment and Renewal Terms:**

- **Pro licenses** are billed monthly or annually at Your choice. Annual billing provides two months free (pay for 10
  months, receive 12 months of access).
- **Enterprise licenses** are billed annually. Volume discounts may be available for large deployments; contact the
  Licensor for details.
- Licenses renew automatically unless cancelled at least thirty (30) days before the renewal date. You may cancel at
  any time through Your account dashboard.
- Upon cancellation or non-renewal, paid features will be disabled at the end of the current billing period. Your data
  and configurations will be retained for ninety (90) days to allow for reactivation or export.

**Payment Failure**: If a payment fails, the Licensor will attempt to process the payment up to three (3) times over a
fourteen (14) day period. You will receive email notification of each failed attempt. If payment cannot be collected
after the retry period, Your license will be downgraded to Core tier and paid features will be disabled. To restore
access, update Your payment method and contact support.

If You wish to use Pro Features or Enterprise Features, You must purchase the appropriate license directly from the
Licensor. Commercial purchase terms are provided at the time of purchase.

**Seat Limits**: Each Pro or Enterprise license includes a defined number of seats. A "seat" represents one unique user
(identified by machine or account). When all licensed seats are in use, additional users will be unable to access paid
features until either: (a) an existing seat is released (via deactivation), or (b) additional seats are purchased. Core
functionality remains available to all users regardless of seat limits.

**Grace Periods**: If the Software cannot validate Your license due to network unavailability, You will have a thirty
(30) day grace period during which paid features remain accessible. After the grace period expires, paid features will
be disabled until validation succeeds. This grace period is designed to prevent disruption to CI/CD pipelines and
offline development environments.

## Runtime Redistribution Requires Per-User Licensing

**"Runtime Redistribution"** means incorporating the Software into a product or service that invokes the Software on
behalf of end users at runtime.

If Your product or service performs Runtime Redistribution, **each end user who benefits from the Software's
functionality must have their own valid commercial license from the Licensor.**

Examples:
- A SaaS code formatting service that uses CAT →each user needs a license
- A hosted IDE that invokes CAT for users →each user needs a license
- A CI/CD platform that runs CAT for customers →each customer needs a license

**Internal tooling exception**: If Your organization uses CAT solely for internal development purposes (e.g.,
formatting Your own codebase during build), and Your product does not invoke CAT at runtime for end users, then Your
end users do not require their own licenses.

## Derivative Works and Commercial Use

**Commercial Use of any Derivative Work also requires a paid license from the original Licensor (not the creator of the
Derivative Work).**

If You create a Derivative Work and another party wishes to use that Derivative Work for Commercial Use, that party
must obtain a commercial license from the original Licensor. Fork maintainers and Derivative Work creators cannot grant
commercial use rights.

This ensures that:
- The original author is compensated for all Commercial Use
- Fork maintainers cannot commercially exploit the original work
- Commercial users always know to contact the original Licensor

## Conditions

You must:

1. **Preserve Notices**: Keep all copyright, license, and attribution notices intact in all copies and Derivative
   Works.

2. **Include License**: Distribute copies of this license with any distribution of the Software or Derivative Works.

3. **State Changes**: If You distribute a Derivative Work, clearly indicate that changes were made and provide
   attribution to the original Software.

4. **No Trademark Rights**: This license does not grant any rights to use the Licensor's trademarks, trade names, or
   service marks.

## No Circumvention

You may not:

1. Remove, alter, or obscure any licensing or copyright notices
2. Use technical measures to restrict others from exercising rights under this license that they would otherwise have
3. Sublicense the Software or grant commercial use rights to third parties
4. Forge, tamper with, or create counterfeit license keys
5. Share license keys across organizations or redistribute keys publicly
6. Bypass, disable, or interfere with the license validation system
7. Misrepresent the number of seats or users accessing the Software

## Disclaimer of Warranty

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. THE LICENSOR MAKES NO WARRANTY
THAT THE SOFTWARE WILL BE ERROR-FREE OR UNINTERRUPTED.

## Limitation of Liability

IN NO EVENT SHALL THE LICENSOR BE LIABLE FOR ANY CLAIM, DAMAGES, OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT, OR OTHERWISE, ARISING FROM, OUT OF, OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Termination

Your rights under this license terminate automatically if You fail to comply with any of its terms. Upon termination,
You must cease all use and distribution of the Software and destroy all copies in Your possession.

## No Automatic Conversion

This license does not contain any provision for automatic conversion to an open-source license. The terms of this
license remain in effect indefinitely unless explicitly changed by the Licensor through a new license version.

## Governing Law

This license shall be governed by and construed in accordance with the laws of the jurisdiction in which the Licensor
resides, without regard to its conflict of law provisions.

## Contact

For commercial licensing inquiries, contact the Licensor at cowwoc2020@gmail.com or through the project's official
repository.

---

Copyright (c) 2026 Gili Tzabari. All rights reserved.
