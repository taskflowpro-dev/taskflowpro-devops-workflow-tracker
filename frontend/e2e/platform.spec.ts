import { expect, test } from "@playwright/test";

test("engineering workflow persists through the actual API and database", async ({
  page,
}, testInfo) => {
  test.skip(
    !process.env.E2E_EMAIL || !process.env.E2E_PASSWORD,
    "Provide an isolated account with a connected repository.",
  );
  const errors: string[] = [];
  const projectName = `E2E delivery review ${Date.now()}`;
  const environmentName = `E2E staging ${Date.now()}`;
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/login");
  await page
    .getByLabel("Email address", { exact: true })
    .fill(process.env.E2E_EMAIL!);
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.E2E_PASSWORD!);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: /Welcome back/ }),
  ).toBeVisible();
  await expect(
    page.locator(".overview-repositories > button").first(),
  ).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("overview-desktop.png"),
    fullPage: true,
  });

  const nav = page.getByRole("navigation", { name: "Main navigation" });
  await nav.getByRole("link", { name: /AI DevOps Architect/ }).click();
  await page
    .getByRole("button", { name: /Generate roadmap|Sync evidence/ })
    .click();
  await expect(page.locator(".recommendation").first()).toBeVisible();
  await page.locator(".recommendation > summary").first().click();
  await page
    .locator(".implementation-step input[type=checkbox]")
    .first()
    .check();
  await expect(
    page.locator(".implementation-step input[type=checkbox]").first(),
  ).toBeChecked();
  await page
    .getByRole("textbox", { name: "Ask the repository assistant" })
    .fill("How should I deploy this project?");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(page.locator(".chat-message.assistant").last()).toContainText(
    "rules-based",
    { timeout: 20000 },
  );
  await page.screenshot({
    path: testInfo.outputPath("architect-desktop.png"),
    fullPage: true,
  });

  await nav
    .getByRole("link", { name: "Workflow Builder", exact: true })
    .click();
  await page.getByRole("button", { name: "Generate configuration" }).click();
  await expect(page.locator(".configuration-preview pre")).toContainText(
    "TaskFlow engineering checks",
  );
  await page.getByRole("button", { name: "Validate configuration" }).click();
  await expect(page.getByText("Static checks passed")).toBeVisible();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download", exact: true }).click();
  expect((await downloadPromise).suggestedFilename()).toBe("taskflow-ci.yml");
  await page.screenshot({
    path: testInfo.outputPath("builder-desktop.png"),
    fullPage: true,
  });

  await nav.getByRole("link", { name: "Projects", exact: true }).click();
  await page.getByRole("button", { name: /New project/ }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByLabel("Project name", { exact: true }).fill(projectName);
  await page
    .getByRole("button", { name: "Create project", exact: true })
    .click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(page.getByRole("heading", { name: projectName })).toBeVisible();
  await page.getByRole("button", { name: /New project/ }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).not.toBeVisible();

  await nav
    .getByRole("link", { name: "Deployment Center", exact: true })
    .click();
  await page
    .getByLabel("Environment name", { exact: true })
    .fill(environmentName);
  await page.getByRole("button", { name: "Create delivery plan" }).click();
  await expect(page.getByText(environmentName, { exact: true })).toBeVisible();
  const savedPlan = page.locator(".saved-plan").first();
  if (
    !(await savedPlan
      .getByRole("button", { name: "Mark plan reviewed" })
      .isVisible())
  )
    await savedPlan.locator("summary").first().click();
  await expect(
    savedPlan.getByRole("button", { name: "Mark plan reviewed" }),
  ).toBeVisible();
  await savedPlan.getByRole("button", { name: "Mark plan reviewed" }).click();
  await expect(page.locator(".saved-plan > summary").first()).toContainText(
    "reviewed",
  );

  await nav.getByRole("link", { name: "Preferences", exact: true }).click();
  const compactSwitch = page.getByRole("switch", { name: /Compact spacing/ });
  await expect(compactSwitch).toBeVisible();
  if (await compactSwitch.isChecked()) {
    await compactSwitch.uncheck();
    await expect(page.getByRole("status")).toContainText("Preferences saved");
  }
  await compactSwitch.check();
  await expect(page.getByRole("status")).toContainText("Preferences saved");
  await page.reload();
  await expect(
    page.getByRole("switch", { name: /Compact spacing/ }),
  ).toBeChecked();
  await expect(page.locator("html")).toHaveAttribute("data-compact", "true");

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "Open navigation" }).click();
  await nav.getByRole("link", { name: "Overview", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: /Welcome back/ }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Open navigation" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await expect(page.locator(".workspace-sidebar")).toHaveCSS(
    "transform",
    "matrix(1, 0, 0, 1, -258, 0)",
  );
  await page.screenshot({
    path: testInfo.outputPath("overview-mobile.png"),
    fullPage: true,
    animations: "disabled",
  });
  expect(errors).toEqual([]);
});

test("all navigation views and team creation use real workspace data", async ({
  page,
}) => {
  test.skip(
    !process.env.E2E_EMAIL || !process.env.E2E_PASSWORD,
    "Provide an isolated test account.",
  );
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/login");
  await page
    .getByLabel("Email address", { exact: true })
    .fill(process.env.E2E_EMAIL!);
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.E2E_PASSWORD!);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: /Welcome back/ }),
  ).toBeVisible();
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  for (const name of [
    "Repository Analyzer",
    "CI/CD Pipelines",
    "Code Health",
    "Security Center",
    "Dependencies",
    "Pull Requests",
    "Implementation Guides",
    "Documentation",
    "Activity History",
    "Account",
    "Integrations",
  ]) {
    await nav.getByRole("link", { name, exact: true }).click();
    await expect(
      page.getByRole("heading", { name, exact: true, level: 1 }),
    ).toBeVisible();
    await expect(page.locator(".workspace-error")).toHaveCount(0);
    await expect(page.locator(".form-error")).toHaveCount(0);
  }
  await nav.getByRole("link", { name: "Teams", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Your teams", exact: true }),
  ).toBeVisible();
  const name = `E2E platform team ${Date.now()}`;
  await page.getByLabel("Team name", { exact: true }).fill(name);
  await page.getByRole("button", { name: "Create team", exact: true }).click();
  await expect(
    page.getByRole("combobox", { name: "Switch workspace" }),
  ).toHaveValue(/^[0-9]+$/);
  await nav.getByRole("link", { name: "Projects", exact: true }).click();
  await expect(page.locator(".project-grid")).toHaveCount(0);
  await nav.getByRole("link", { name: /Team members/ }).click();
  await expect(
    page.getByRole("heading", { name: "Team members", exact: true, level: 1 }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Send invitation" }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});
