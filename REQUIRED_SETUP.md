# wscan+ — Required Setup & Permissions

This document lists every secret, variable, workflow permission, and tool
integration that is needed to make all CI/CD workflows fully green.  
The **Needs Attention** workflow will link here whenever a check is blocked.

---

## 1. Repository Secrets

Configure at **Settings → Secrets and variables → Actions → Secrets**.

### 1.1 APK / AAB Signing (Android Release workflow)

| Secret | Required? | Description | How to obtain |
|---|---|---|---|
| `KEYSTORE_BASE64` | Optional* | Base-64 encoded release keystore (`.jks` file) | `base64 -w0 your-release.jks` then paste the output |
| `KEYSTORE_STORE_PASSWORD` | Optional* | Password for the keystore file | Set when you ran `keytool -genkey` |
| `KEYSTORE_KEY_ALIAS` | Optional* | Alias of the signing key inside the keystore | The `-alias` value from `keytool -genkey` |
| `KEYSTORE_KEY_PASSWORD` | Optional* | Password for the individual key | Often the same as the store password |

> **\*Optional** — without these, the pipeline produces an **unsigned debug APK** which works
> for sideloading but cannot be uploaded to the Play Store.  
> Generate a new keystore with:
> ```bash
> keytool -genkey -v -keystore wscanplus-release.jks \
>   -alias wscanplus -keyalg RSA -keysize 2048 -validity 10000
> ```

### 1.2 SonarCloud (Code Quality / SonarCloud Analysis workflows)

| Secret | Required? | Description | How to obtain |
|---|---|---|---|
| `SONAR_TOKEN` | **Required** for SonarCloud | API token that authenticates to SonarCloud | 1. Sign in at [sonarcloud.io](https://sonarcloud.io) with your GitHub account.<br>2. Go to **My Account → Security → Generate Tokens**.<br>3. Copy the token and add it here. |

---

## 2. Repository Variables

Configure at **Settings → Secrets and variables → Actions → Variables**.

| Variable | Required? | Value | Purpose |
|---|---|---|---|
| `SONAR_ORGANIZATION` | **Required** for SonarCloud | Your SonarCloud org slug (e.g. `dvntone`) | Identifies your org in SonarCloud project keys |

---

## 3. GitHub Actions Workflow Permissions

The default `GITHUB_TOKEN` permissions are intentionally minimal.  
Some workflows need elevated permissions to post PR comments and create issues.

### 3.1 Needed token permissions

| Scope | Level needed | Which workflow |
|---|---|---|
| `contents` | `read` | All |
| `contents` | `write` | Android Release (create GitHub Releases), Windows Release |
| `pull-requests` | `write` | **Needs Attention** (post PR comments), Code Quality Gate (post summary) |
| `issues` | `write` | **Needs Attention** (create tracking issues) |
| `actions` | `read` | **Needs Attention** (read workflow-run logs) |

### 3.2 How to set the default token permission level

1. Go to **Settings → Actions → General**.
2. Under **Workflow permissions**, select **Read and write permissions**.
3. Check **Allow GitHub Actions to create and approve pull requests**.
4. Save.

> **Why this matters**: without `pull-requests: write`, the **Needs Attention**
> workflow cannot post a comment on your PR when a check fails.  
> Without `issues: write`, it cannot create a tracking issue when there is no PR.

---

## 4. Third-party Integrations

### 4.1 SonarCloud (recommended)

SonarCloud provides continuous static analysis, security scanning, and code
coverage tracking with a free tier for public repositories.

**One-time setup:**
1. Visit [sonarcloud.io](https://sonarcloud.io) → **+** → **Analyze new project**.
2. Import the `dvntone/wscanPLUS` repository.
3. Choose **With GitHub Actions** as the analysis method.
4. Copy the generated `SONAR_TOKEN` into the repository secret (§1.2 above).
5. Set `SONAR_ORGANIZATION` to your org slug (§2 above).
6. The `sonarcloud.yml` workflow will handle the rest.

**Dashboard URL (once set up):**  
`https://sonarcloud.io/project/overview?id=dvntone_wscanPLUS_android`  
`https://sonarcloud.io/project/overview?id=dvntone_wscanPLUS_windows`

### 4.2 Reviewdog (alternative lightweight linting on PRs)

If SonarCloud is too heavy, [reviewdog](https://github.com/reviewdog/reviewdog)
posts inline lint annotations directly on PR diffs.  
The `code-quality.yml` workflow already uploads ktlint and lint XML reports
which can be fed to reviewdog without any extra secret.

---

## 5. Permissions I (the Copilot agent) Would Benefit From

The following would let the automated agent resolve more issues without
needing to ask you first:

| Permission / Secret | Benefit |
|---|---|
| `pull-requests: write` (default token) | Post status comments on PRs automatically (already requested in §3) |
| `issues: write` (default token) | Create action-required tracking issues (already requested in §3) |
| `SONAR_TOKEN` + `SONAR_ORGANIZATION` | Run SonarCloud in CI so security findings are surfaced before merge |
| Keystore secrets | Produce signed APKs in CI — required before any Play Store submission |
| A **Personal Access Token (PAT)** with `repo` + `workflow` scopes stored as `GH_PAT` | Would allow the agent to re-trigger failed workflows automatically after fixing code |

---

## 6. What Each Workflow Does (and What It Needs)

| Workflow file | Trigger | Needs secrets | Purpose |
|---|---|---|---|
| `android.yml` | push/PR to `android/` | None | Build + unit test debug APK |
| `windows.yml` | push/PR to `windows/` | None | Build + unit test .NET solution |
| `android-release.yml` | version tags / manual | `KEYSTORE_*` (optional) | Full release pipeline: sign APK, create GitHub Release |
| `code-quality.yml` | every push/PR | None | ktlint, Android Lint, .NET WarningLevel 5, publish check |
| `sonarcloud.yml` | every push/PR | `SONAR_TOKEN`, `SONAR_ORGANIZATION` | Deep static analysis + security hotspot detection |
| `notify-waiting.yml` | other workflows fail | `pull-requests: write` token | Post PR/issue notification when action is required |

---

## 7. Quick-start Checklist

- [ ] Set **Workflow permissions** to Read & Write in Settings → Actions → General
- [ ] Enable **Allow GitHub Actions to create and approve pull requests**
- [ ] (Optional) Generate a keystore and add the four `KEYSTORE_*` secrets
- [ ] (Recommended) Create a SonarCloud account, import the repo, add `SONAR_TOKEN` + `SONAR_ORGANIZATION`
- [ ] Merge this PR — all green checks on `main` will trigger the `build-confirmed.txt` artifact for the in-app update checker
