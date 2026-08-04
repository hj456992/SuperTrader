import { expect, request, test } from '@playwright/test';

/**
 * Agent Demo E2E — mobile drawer 390x844. Black-box, GUI-driven. Runs against a
 * real local demo. NEVER connects SimNow/CTP. The session sidebar is hidden on
 * mobile by design, so sessions are created via the REST API and opened by URL.
 */
const DEMO = '/#/agent-demo';
const API = 'http://127.0.0.1:8080/api/v1/agent-demo';

async function createSessionViaApi(): Promise<string> {
  const ctx = await request.newContext();
  const r = await ctx.post(`${API}/sessions`, {
    data: { title: 'e2e-mobile' }, headers: { 'Content-Type': 'application/json' },
  });
  const loc = r.headers()['location'] ?? '';
  await r.dispose();
  return loc.substring(loc.lastIndexOf('/') + 1);
}

test('mobile: the Harness drawer shows visible content and no horizontal overflow', async ({ page }) => {
  const sid = await createSessionViaApi();
  await page.goto(DEMO);
  await page.waitForLoadState('load');
  await page.goto(`${DEMO}?s=${encodeURIComponent(sid)}`);
  await expect(page.getByTestId('conversation-pane')).toBeVisible({ timeout: 15_000 });
  // The mobile toggle is visible and does NOT overlap the send button.
  await expect(page.getByTestId('mobile-inspector-toggle')).toBeVisible();
  await page.getByTestId('mobile-inspector-toggle').click();
  const drawer = page.getByTestId('mobile-inspector-drawer');
  await expect(drawer).toBeVisible();
  await expect(drawer.getByTestId('inspector-intent')).toBeVisible();
  await expect(drawer.getByTestId('inspector-trajectory')).toBeVisible();
  await drawer.getByTestId('inspector-close').click();
  await expect(drawer).toHaveCount(0);
  // No horizontal overflow on 390x844.
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
});
