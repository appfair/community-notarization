import { test, expect } from "@playwright/test";
const openInstall = async (page: import("@playwright/test").Page) => {
  await page
    .getByRole("button", { name: "Install", exact: true })
    .first()
    .click();
};
test.beforeEach(async ({ page }) => {
  await page.goto("./");
});
test("unanimous approval installs without confirmation and reset clears the home screen", async ({
  page,
}) => {
  await openInstall(page);
  await expect(
    page.getByRole("heading", { name: "App installed", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Done", exact: true }).click();
  await expect(page.locator('[data-app="calculator"]')).toBeVisible();
  await page.getByRole("button", { name: "Reset simulator" }).click();
  await expect(page.locator("#installed-apps")).toBeEmpty();
});
for (const id of ["social", "patterns", "bank", "stealer", "nudificator"]) {
  test(`${id}: displays every active assessment and cancellation does not install`, async ({
    page,
  }) => {
    await page.locator("#app-select").selectOption(id);
    await openInstall(page);
    await expect(
      page.getByRole("heading", { name: "Before you install" }),
    ).toBeVisible();
    await expect(page.locator(".dialog-verdict")).toHaveCount(5);
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(page.locator("#installed-apps")).toBeEmpty();
    await openInstall(page);
    await page.locator("#confirm-button").click();
    await expect(
      page.getByRole("heading", { name: "App installed", exact: true }),
    ).toBeVisible();
    await page.locator("#done-button").click();
    await expect(page.locator(`[data-app="${id}"]`)).toBeVisible();
  });
}
test("age advice is visible and keyboard focus stays inside the review", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("social");
  await openInstall(page);
  await expect(page.locator(".dialog-verdict.age16")).toContainText(
    "ages 16 and up",
  );
  await expect(page.locator("#cancel-button")).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(page.locator("#review-timeout-button")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.locator("#cancel-button")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.locator("#dialog-overlay")).toBeHidden();
  await expect(page.locator("#installed-apps")).toBeEmpty();
});
test("ADV-only revocation is marked as undocumented", async ({ page }) => {
  await page.locator("#app-select").selectOption("bank");
  for (const id of ["vt", "fossr", "cr", "cs"])
    await page.locator(`input[value="${id}"]`).uncheck();
  await openInstall(page);
  await expect(
    page.getByRole("heading", { name: "Revocation behavior not specified" }),
  ).toBeVisible();
  await expect(page.locator("#confirm-button")).toBeHidden();
  await page.locator("#done-button").click();
  await expect(page.locator("#installed-apps")).toBeEmpty();
});
test("ADV remains active when all optional notaries are off", async ({
  page,
}) => {
  const adv = page.locator('input[value="adv"]');
  await expect(adv).toBeChecked();
  await expect(adv).toBeDisabled();
  for (const id of ["vt", "fossr", "cr", "cs"])
    await page.locator(`input[value="${id}"]`).uncheck();
  await expect(page.locator("#active-count")).toHaveText("1 of 5 active");
  await page.locator("#app-select").selectOption("custom");
  await expect(page.locator('[data-response="0"]')).toBeEnabled();
  await expect(page.locator("#prediction-title")).toHaveText(
    "No additional ADV restriction",
  );
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App installed");
  await page.locator("#done-button").click();
  await expect(adv).toBeChecked();
  await expect(adv).toBeDisabled();
  await page.locator("#reset-button").click();
  await expect(adv).toBeChecked();
  await expect(adv).toBeDisabled();
});
test("changing active notaries changes the outcome and reinstall creates no duplicates", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("social");
  await page.locator('input[value="fossr"]').uncheck();
  await page.locator('input[value="cs"]').uncheck();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Install automatically",
  );
  for (let i = 0; i < 2; i++) {
    await openInstall(page);
    await expect(page.locator("#dialog-title")).toHaveText("App installed");
    await page.locator("#done-button").click();
  }
  await expect(page.locator('[data-app="social"]')).toHaveCount(1);
});
test("mobile layout fits and dialogs remain actionable", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator("#app-select").selectOption("nudificator");
  await openInstall(page);
  await expect(page.locator("#cancel-button")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.locator("#cancel-button").click();
});
test("custom responses control the preview and installation dialog", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("custom");
  const responses = page.locator(".custom-response");
  await expect(responses).toHaveCount(5);
  for (const response of await responses.all())
    await expect(response).toBeVisible();
  await expect(page.locator('[data-verdict="0"]')).toBeHidden();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Install automatically",
  );
  await responses.nth(0).selectOption("revoked");
  await responses.nth(1).selectOption("disapprove");
  await responses.nth(2).selectOption("abstain");
  await responses.nth(4).selectOption("age16");
  await expect(page.locator("#prediction-title")).toHaveText(
    "Request confirmation",
  );
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("Before you install");
  await expect(responses.nth(0)).toBeDisabled();
  for (const verdict of [
    "revoke",
    "disapprove",
    "abstain",
    "approve",
    "age16",
  ]) {
    await expect(page.locator(`.dialog-verdict.${verdict}`)).toHaveCount(1);
    if (verdict !== "revoke")
      await expect(page.locator(`.dialog-verdict.${verdict}`)).toContainText(
        "custom scenario",
      );
  }
  await page.locator("#cancel-button").click();
  await expect(page.locator("#installed-apps")).toBeEmpty();
  await openInstall(page);
  await page.locator("#confirm-button").click();
  await expect(page.locator("#dialog-title")).toHaveText("App installed");
});
test("custom unanimous verdicts install or block without a decision", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("custom");
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App installed");
  await page.locator("#done-button").click();
  await page.locator("#reset-button").click();
  await page.locator("#app-select").selectOption("custom");
  await page.locator('[data-response="0"]').selectOption("unregistered");
  for (const response of await page
    .locator('.custom-response:not([data-response="0"])')
    .all())
    await response.selectOption("disapprove");
  await expect(page.locator("#prediction-title")).toHaveText(
    "Block installation",
  );
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App not installed");
  await expect(page.locator("#dialog-verdicts")).toBeEmpty();
  await page.locator("#done-button").click();
  await page.locator("#report-notification").click();
  await expect(page.locator("#dialog-title")).toHaveText(
    "Installation blocked",
  );
  await expect(page.locator(".dialog-verdict")).toHaveCount(5);
  await expect(page.locator("#confirm-button")).toBeHidden();
  await expect(page.locator("#installed-apps")).toBeEmpty();
});
test("custom responses persist across app switches and reset to approval", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("custom");
  const response = page.locator('[data-response="1"]');
  await response.selectOption("disapprove");
  await expect(page.locator('input[value="vt"]')).toBeChecked();
  await page.locator('input[value="vt"]').uncheck();
  await expect(response).toBeDisabled();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Install automatically",
  );
  await page.locator("#app-select").selectOption("calculator");
  await expect(response).toBeHidden();
  await expect(page.locator('[data-verdict="1"]')).toHaveText("Inactive");
  await page.locator("#app-select").selectOption("custom");
  await expect(response).toHaveValue("disapprove");
  await page.locator('input[value="vt"]').check();
  await expect(response).toBeEnabled();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Request confirmation",
  );
  await page.locator("#reset-button").click();
  await page.locator("#app-select").selectOption("custom");
  await expect(page.locator('[data-response="0"]')).toHaveValue("registered");
  for (const control of await page
    .locator('.custom-response:not([data-response="0"])')
    .all())
    await expect(control).toHaveValue("approve");
});
test("only Common Sensical Media offers the Age 16+ response", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("custom");
  for (let i = 0; i < 4; i++) {
    await expect(
      page.locator(`[data-response="${i}"] option[value="age16"]`),
    ).toHaveCount(0);
  }
  const cs = page.locator('[data-response="4"]');
  await expect(cs.locator('option[value="age16"]')).toHaveCount(1);
  await cs.selectOption("age16");
  await expect(page.locator("#prediction-title")).toHaveText(
    "Request confirmation",
  );
  await openInstall(page);
  await expect(page.locator(".dialog-verdict.age16")).toContainText(
    "Common Sensical Media",
  );
});
test("ADV-only unregistered installation completes the simulated advanced flow once", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("custom");
  for (const id of ["vt", "fossr", "cr", "cs"])
    await page.locator(`input[value="${id}"]`).uncheck();
  await page.locator('[data-response="0"]').selectOption("unregistered");
  await expect(page.locator("#install-label")).toHaveText(
    "Install (high-friction)",
  );
  await expect(page.locator("#adv-notice")).toContainText("24 hours");
  await page.locator("#install-button").click();
  await expect(page.locator("#dialog-title")).toHaveText(
    "Advanced flow required",
  );
  await page.locator("#adv-action-button").click();
  await expect(page.locator("#dialog-title")).toHaveText(
    "24-hour security delay",
  );
  await expect(page.locator("#installed-apps")).toBeEmpty();
  await page.locator("#adv-action-button").click();
  await expect(page.locator("#dialog-title")).toHaveText(
    "Complete advanced-flow setup",
  );
  await page.locator("#adv-duration").selectOption("indefinite");
  await page.locator("#adv-action-button").click();
  await expect(page.locator("#dialog-title")).toHaveText(
    "App from an unverified developer",
  );
  await expect(page.locator("#adv-action-button")).toHaveText("Install anyway");
  await page.locator("#adv-action-button").click();
  await expect(page.locator("#dialog-title")).toHaveText("App installed");
  await page.locator("#done-button").click();
  await expect(page.locator("#install-label")).toHaveText("Install anyway");
  await page.locator("#install-button").click();
  await expect(page.locator("#dialog-title")).toHaveText(
    "App from an unverified developer",
  );
  await page.locator("#cancel-button").click();
  await page.locator("#reset-button").click();
  await expect(page.locator("#adv-flow")).toHaveValue("off");
});

test("specification follows the simulator and exposes five accessible vector diagrams", async ({
  page,
}) => {
  await expect(page).toHaveTitle("Community App Notarization");
  await expect(page.locator(".draft-status h1")).toHaveText(
    "Community App Notarization Specification - Confidential Draft | R9",
  );
  await expect(page.locator('#specification svg[role="img"]')).toHaveCount(5);
  const order = await page.evaluate(
    () =>
      !!(
        document
          .querySelector("#simulator")!
          .compareDocumentPosition(document.querySelector("#specification")!) &
        Node.DOCUMENT_POSITION_FOLLOWING
      ),
  );
  expect(order).toBe(true);
  const formLayout = await page.locator("#install-form").evaluate((form) => ({
    overflow: getComputedStyle(form).overflowY,
    contentHeight: form.scrollHeight,
    height: form.clientHeight,
  }));
  expect(formLayout.overflow).toBe("visible");
  expect(formLayout.contentHeight).toBeLessThanOrEqual(formLayout.height + 1);
});

test("offline installation uses valid cached evidence, then asks after expiry", async ({
  page,
}) => {
  await openInstall(page);
  await page.locator("#done-button").click();
  await page.locator("#simulation-settings summary").click();
  await page.locator("#connection").selectOption("offline");
  await expect(page.locator("#prediction-title")).toHaveText(
    "Install automatically",
  );
  await openInstall(page);
  await expect(page.locator(".evidence-source")).toHaveText([
    "Cached assessment · Unavailable",
    "Cached assessment · Unavailable",
    "On-device result",
    "Cached assessment · Unavailable",
    "Cached assessment · Unavailable",
  ]);
  await page.locator("#done-button").click();
  await page.locator("#advance-day").click();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Request confirmation",
  );
  await openInstall(page);
  await expect(page.locator(".dialog-verdict.unavailable")).toHaveCount(4);
  await page.locator("#cancel-button").click();
});

test("review is required again after installation or cancellation of the same APK", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("custom");
  await page.locator('[data-response="1"]').selectOption("disapprove");
  await openInstall(page);
  await expect(
    page.locator('#install-dialog input[type="checkbox"]'),
  ).toHaveCount(0);
  await page.locator("#confirm-button").click();
  await page.locator("#done-button").click();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Request confirmation",
  );
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("Before you install");
  await page.locator("#cancel-button").click();
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("Before you install");
  await page.locator("#cancel-button").click();
  await page.locator("#simulation-settings summary").click();
  await page.locator("#connection").selectOption("offline");
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("Before you install");
  await expect(page.locator(".dialog-verdict.disapprove")).toHaveCount(1);
  await page.locator("#cancel-button").click();
});

test("local provider can answer offline while remote providers remain unavailable", async ({
  page,
}) => {
  await page.locator("#simulation-settings summary").click();
  await page.locator("#route-fossr").selectOption("service-local");
  await page.locator("#connection").selectOption("offline");
  await openInstall(page);
  await expect(page.locator(".dialog-verdict.approve")).toHaveCount(1);
  await expect(page.locator(".dialog-verdict.approve")).toContainText(
    "On-device result",
  );
  await expect(page.locator(".dialog-verdict.unavailable")).toHaveCount(4);
  await page.locator("#review-timeout-button").click();
  await expect(page.locator("#installed-apps")).toBeEmpty();
});

test("saved attestation comes from a provider and expires without renewal", async ({
  page,
}) => {
  await page.locator("#simulation-settings summary").click();
  await page.locator("#route-fossr").selectOption("service-store");
  await page.locator("#prepare-evidence").click();
  await expect(page.locator("#installed-apps")).toBeEmpty();
  await page.locator("#connection").selectOption("offline");
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App installed");
  await expect(page.locator(".evidence-source").nth(2)).toHaveText(
    "Attestation from provider app",
  );
  await page.locator("#done-button").click();
  await page.locator("#advance-day").click();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Request confirmation",
  );
});

test("timeout and dismissal leave the next installation requiring review", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("social");
  await openInstall(page);
  await page.locator("#review-timeout-button").click();
  await expect(page.locator("#dialog-title")).toHaveText("App not installed");
  await page.locator("#done-button").click();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Request confirmation",
  );
  await openInstall(page);
  await page.keyboard.press("Escape");
  await expect(page.locator("#prediction-title")).toHaveText(
    "Request confirmation",
  );
  await expect(page.locator("#installed-apps")).toBeEmpty();
});

test("ADV seven-day allowance expires in simulated time", async ({ page }) => {
  await page.locator("#app-select").selectOption("custom");
  for (const id of ["vt", "fossr", "cr", "cs"])
    await page.locator(`input[value="${id}"]`).uncheck();
  await page.locator('[data-response="0"]').selectOption("unregistered");
  await page.locator("#adv-settings summary").click();
  await page.locator("#adv-flow").selectOption("seven-days");
  await expect(page.locator("#install-label")).toHaveText("Install anyway");
  await page.locator("#advance-seven").click();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Advanced-flow allowance expired",
  );
  await page.locator("#install-button").click();
  await expect(page.locator("#adv-action-button")).toBeHidden();
  await expect(page.locator("#installed-apps")).toBeEmpty();
});

for (const allowance of ["seven-days", "indefinite"]) {
  test(`native ADV retains the ${allowance} allowance and requires confirmation`, async ({
    page,
  }) => {
    await page.locator("#app-select").selectOption("custom");
    for (const id of ["vt", "fossr", "cr", "cs"])
      await page.locator(`input[value="${id}"]`).uncheck();
    await page.locator('[data-response="0"]').selectOption("unregistered");
    await page.locator("#adv-settings summary").click();
    await page.locator("#adv-flow").selectOption(allowance);
    await page.locator("#platform-settings summary").click();
    await page.locator("#platform").selectOption("platform");
    await page.locator("#install-button").click();
    await expect(page.locator("#installed-apps")).toBeEmpty();
    await expect(page.locator("#adv-action-button")).toHaveText(
      "Install anyway",
    );
    await page.locator("#adv-action-button").click();
    await expect(page.locator("#dialog-title")).toHaveText("App installed");
    await page.locator("#done-button").click();
    await expect(page.locator('[data-response="0"]')).toHaveValue(
      "unregistered",
    );
    await page.locator('input[value="vt"]').check();
    await page.locator("#install-button").click();
    await expect(page.locator("#confirm-button")).toBeHidden();
    await expect(page.locator("#adv-action-button")).toBeHidden();
    await expect(page.locator("#dialog-title")).toHaveText(
      "System ADV outcome unresolved",
    );
  });
}

test("independent platform ADV blocks community review until integration is coordinated", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("patterns");
  await page.locator("#platform-settings summary").click();
  await page.locator("#platform").selectOption("platform");
  await expect(page.locator("#prediction-title")).toHaveText(
    "Blocked by platform ADV",
  );
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App not installed");
  await expect(page.locator("#confirm-button")).toBeHidden();
  await page.locator("#done-button").click();
  await page.locator("#report-notification").click();
  await expect(page.locator("#dialog-title")).toHaveText(
    "Blocked by platform ADV",
  );
  await page.locator("#done-button").click();
  await page.locator("#coordination").selectOption("coordinated");
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("Before you install");
  await page.locator("#confirm-button").click();
  await expect(page.locator("#dialog-title")).toHaveText("App installed");
});

test("older requesting installer delegates native ADV failure to the system", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("patterns");
  await page.locator("#platform-settings summary").click();
  await page.locator("#platform").selectOption("platform");
  await page.locator("#installer-target").selectOption("legacy-user");
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText(
    "System ADV outcome unresolved",
  );
  await expect(page.locator("#confirm-button")).toBeHidden();
  await expect(page.locator("#installed-apps")).toBeEmpty();
});

test("incremental delivery rejects missing evidence and uses prepared cached approval", async ({
  page,
}) => {
  await page.locator("#platform-settings summary").click();
  await page.locator("#delivery").selectOption("incremental");
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App not installed");
  await expect(page.locator("#confirm-button")).toBeHidden();
  await page.locator("#done-button").click();
  await page.locator("#simulation-settings summary").click();
  await page.locator("#prepare-evidence").click();
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App installed");
  await expect(page.locator(".evidence-source")).toHaveText(
    Array(5).fill("Cached assessment"),
  );
  await page.locator("#done-button").click();
  await page.locator("#identity-available").uncheck();
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App not installed");
  await page.locator("#done-button").click();
  await page.locator("#identity-available").check();
  await page.locator("#advance-day").click();
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App not installed");
});

test("mixed incremental assessments cannot open a review dialog", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("social");
  await page.locator("#simulation-settings summary").click();
  await page.locator("#prepare-evidence").click();
  await page.locator("#platform-settings summary").click();
  await page.locator("#delivery").selectOption("incremental");
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App not installed");
  await expect(page.locator("#confirm-button")).toBeHidden();
  await page.locator("#done-button").click();
  await page.locator("#report-notification").click();
  await expect(page.locator(".dialog-verdict.age16")).toHaveCount(1);
  await expect(page.locator("#confirm-button")).toBeHidden();
});

test("failed deadline extension rejects before collecting evidence", async ({
  page,
}) => {
  await page.locator("#platform-settings summary").click();
  await page.locator("#extension-available").uncheck();
  await expect(page.locator("#prediction-title")).toHaveText(
    "Verification deadline unavailable",
  );
  await openInstall(page);
  await expect(page.locator("#dialog-title")).toHaveText("App not installed");
  await expect(page.locator("#dialog-verdicts")).toBeEmpty();
  await page.locator("#done-button").click();
  await page.locator("#report-notification").click();
  await expect(page.locator("#dialog-title")).toHaveText(
    "Verification deadline unavailable",
  );
  await expect(page.locator("#confirm-button")).toBeHidden();
});

test("live overview follows app selection, notary activation, and custom assessments", async ({
  page,
}) => {
  const overview = page.locator("#proposal-overview");
  await expect(overview.locator("[data-overview-provider]")).toHaveCount(5);
  await expect(overview.locator("#overview-app")).toHaveText(
    "FreeCalculator.apk",
  );
  await expect(overview.locator("#overview-coordinator-id")).toHaveText(
    "com.android.vending",
  );
  await expect(
    overview.locator('[data-overview-provider="adv"] a'),
  ).toHaveAttribute(
    "href",
    "https://play.google.com/store/apps/details?id=com.google.android.verifier",
  );
  await page.locator("#app-select").selectOption("social");
  await expect(overview.locator("#overview-app")).toHaveText("SocialMedia.apk");
  const cs = overview.locator('[data-overview-provider="cs"]');
  await expect(cs).toHaveAttribute("data-state", "age16");
  await expect(cs.locator("[data-overview-verdict]")).toHaveText("Age 16+");
  await page.locator('input[value="cs"]').uncheck();
  await expect(cs).toHaveAttribute("data-state", "inactive");
  await expect(cs.locator("[data-overview-verdict]")).toHaveText("Inactive");
  await page.locator("#app-select").selectOption("custom");
  await page.locator('[data-response="1"]').selectOption("disapprove");
  await expect(
    overview.locator('[data-overview-provider="vt"]'),
  ).toHaveAttribute("data-state", "disapprove");
  await page.locator("#reset-button").click();
  await expect(cs).toHaveAttribute("data-state", "approve");
  await expect(overview.locator("#overview-app")).toHaveText(
    "FreeCalculator.apk",
  );
});

test("live overview distinguishes offline local evidence, cache, and direct HTTPS", async ({
  page,
}) => {
  const foss = page.locator('[data-overview-provider="fossr"]');
  const vt = page.locator('[data-overview-provider="vt"]');
  await expect(foss.locator("[data-overview-evidence-title]")).toHaveText(
    "On-device database",
  );
  await expect(foss).toHaveAttribute("data-route", "service-local");
  await page.locator("#simulation-settings summary").click();
  await page.locator("#connection").selectOption("offline");
  await expect(foss).toHaveAttribute("data-state", "approve");
  await expect(vt).toHaveAttribute("data-state", "unavailable");
  await page.locator("#connection").selectOption("online");
  await page.locator("#prepare-evidence").click();
  await page.locator("#connection").selectOption("offline");
  await expect(vt).toHaveAttribute("data-state", "approve");
  await expect(vt.locator("[data-overview-source]")).toHaveText(
    "Cached assessment",
  );
  await expect(vt).toHaveAttribute("data-live", "false");
  await expect(page.locator("#overview-cache")).toHaveClass(/is-used/);
  await page.locator("#connection").selectOption("online");
  await page.locator("#route-vt").selectOption("https");
  await expect(vt.locator(".overview-direct-wire")).toBeVisible();
  await expect(vt.locator(".overview-source-wire")).toBeHidden();
  await page.locator("#reset-button").click();
  await expect(foss).toHaveAttribute("data-route", "service-local");
  await expect(vt).toHaveAttribute("data-route", "service-online");
});

test("live overview shows native ADV and incremental limits without changing the predicted outcome", async ({
  page,
}) => {
  await page.locator("#app-select").selectOption("patterns");
  await page.locator("#platform-settings summary").click();
  await page.locator("#platform").selectOption("platform");
  await expect(page.locator("#overview-native")).toBeVisible();
  await expect(page.locator("#overview-outcome")).toHaveText(
    "Expected result: Blocked by platform ADV",
  );
  await page.locator("#coordination").selectOption("coordinated");
  await expect(page.locator("#overview-outcome")).toHaveText(
    "Expected result: Request confirmation",
  );
  await page.locator("#platform").selectOption("aosp");
  await expect(page.locator("#overview-native")).toBeHidden();
  await expect(page.locator("#overview-coordinator-id")).toHaveText(
    "org.appfair.notarization",
  );
  await page.locator("#delivery").selectOption("incremental");
  await expect(page.locator("#overview-outcome")).toHaveText(
    "Expected result: Reject incremental installation",
  );
  await expect(
    page.locator('[data-overview-provider][data-live="true"]'),
  ).toHaveCount(0);
});

test("form, phone, and flow scale together and stay on one row", async ({
  page,
}) => {
  for (const width of [320, 390, 768, 1280, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    await expect
      .poll(async () =>
        page.locator("#simulator").evaluate((row) => {
          const columns = [...row.children].map((column) =>
            column.getBoundingClientRect(),
          );
          const diagram = row
            .querySelector("#overview-diagram")!
            .getBoundingClientRect();
          return (
            columns.length === 3 &&
            columns.every(
              (column) => Math.abs(column.top - columns[0].top) < 1,
            ) &&
            columns[0].right <= columns[1].left &&
            columns[1].right <= columns[2].left &&
            columns[2].right <= innerWidth &&
            diagram.left >= columns[2].left &&
            diagram.right <= columns[2].right &&
            document.documentElement.scrollWidth <= innerWidth
          );
        }),
      )
      .toBe(true);
  }
});
