const assert = require('node:assert/strict');
const { before, after, test } = require('node:test');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../src/main/resources/static');
const artifacts = path.resolve(__dirname, '../build');
const session = { authenticated: true, spotifyConfigured: true, lastFmConfigured: true, lastFmAuthenticated: true };
let browser;
let browserServer;
let server;
let origin;

before(async () => {
  server = http.createServer(async (request, response) => {
    try {
      const filename = new URL(request.url, 'http://localhost').pathname;
      const resolved = path.resolve(root, filename === '/' ? 'index.html' : filename.slice(1));
      if (request.method !== 'GET' || !resolved.startsWith(root + path.sep)) throw new Error('Unexpected request');
      const body = await fs.readFile(resolved);
      const mime = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml' }[path.extname(resolved)];
      response.writeHead(200, { 'Content-Type': mime + '; charset=utf-8' });
      response.end(body);
    } catch { response.writeHead(404); response.end('Not found'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
  browserServer = await chromium.launchServer({ headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : {}) });
  browser = await chromium.connect(browserServer.wsEndpoint());
});

after(async () => {
  if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  // System Chromium may not exit gracefully; this process belongs to this test run.
  await browserServer?.kill();
  await browser?.close();
});

async function createPage(t, state = session) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(5000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  t.after(async () => { await context.close(); assert.deepEqual(errors, [], 'No uncaught JavaScript errors'); });
  await page.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.origin === origin) return route.continue();
    if (url.origin === 'https://open.spotify.com') return route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Test Spotify player</title>' });
    return route.abort();
  });
  await page.route('**/api/session', route => route.fulfill({ json: state }));
  return page;
}

async function ready(page) { await page.goto(origin); await page.waitForFunction(() => document.getElementById('connection').dataset.state !== 'loading'); }
async function enabled(page, id) { await page.waitForFunction(id => !document.getElementById(id).disabled, id); }
function assertPost(route) { assert.equal(route.request().method(), 'POST'); assert.equal(route.request().headers()['x-requested-with'], 'XMLHttpRequest'); }
async function validUsername(page) {
  await page.route('**/verifyLastFmId/**', route => { assertPost(route); return route.fulfill({ json: true }); });
  await page.locator('#lastFmId').fill('musiclover');
  await enabled(page, 'lastfm');
}

test('Session loading blocks every playlist mutation; disconnected visitors get an explicit connection link', async t => {
  const page = await createPage(t, { ...session, authenticated: false });
  let respond;
  const response = new Promise(resolve => { respond = resolve; });
  await page.route('**/api/session', async route => { await response; await route.fulfill({ json: { ...session, authenticated: false } }); });
  await page.goto(origin);
  for (const id of ['top', 'lastfm', 'forgottenObsessions', 'privateMoodTaxonomy', 'bandPlaylist', 'lastFmId']) assert.equal(await page.locator('#' + id).isDisabled(), true);
  assert.equal(await page.locator('#connection').getAttribute('aria-busy'), 'true');
  respond();
  await page.locator('#connectSpotify').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#connectSpotify').getAttribute('href'), '/auth/spotify');
  assert.equal(await page.locator('#disconnectSpotify').isVisible(), false);
  assert.equal(await page.locator('#top').isDisabled(), true);
  await fs.mkdir(artifacts, { recursive: true });
  await page.screenshot({ path: path.join(artifacts, 'ui-disconnected.png'), fullPage: true });
});

test('Missing configuration and a failed session check stay honest and can recover', async t => {
  const page = await createPage(t, { ...session, authenticated: false, spotifyConfigured: false });
  await ready(page);
  assert.equal(await page.locator('#connection').getAttribute('data-state'), 'unconfigured');
  assert.equal(await page.locator('#connectSpotify').isVisible(), false);
  assert.equal(await page.locator('#top').isDisabled(), true);
  await page.route('**/api/session', route => route.fulfill({ status: 503 }));
  await page.locator('#retrySession').click();
  await page.waitForFunction(() => document.getElementById('connection').dataset.state === 'error');
  await page.route('**/api/session', route => route.fulfill({ json: session }));
  await page.locator('#retrySession').click();
  await enabled(page, 'top');
  assert.equal(await page.locator('#disconnectSpotify').isVisible(), true);
});

test('Missing Last.fm configuration only disables the Last.fm workflows', async t => {
  const page = await createPage(t, { ...session, lastFmConfigured: false });
  await ready(page);
  assert.equal(await page.locator('#top').isEnabled(), true);
  assert.equal(await page.locator('#lastFmId').isDisabled(), true);
  assert.match(await page.locator('#usernameHint').textContent(), /not available/i);
  await page.locator('#bandNames').fill('Radiohead, Portishead');
  assert.equal(await page.locator('#bandPlaylist').isEnabled(), true);
});

test('Spotify renders named players and replaces previous results on retry', async t => {
  const page = await createPage(t);
  let count = 0;
  await page.route('**/updateTopPlaylists', route => { assertPost(route); count++; return route.fulfill({ json: [0, 1, 2, 3].map(index => `playlist${count}${index}`) }); });
  await ready(page);
  await page.locator('#top').click();
  await page.waitForFunction(() => document.querySelectorAll('#spotifyTop iframe').length === 4);
  assert.deepEqual(await page.locator('#spotifyTop h3').allTextContents(), ['Short Term', 'Mid Term', 'Long Term', 'Mixed Term']);
  assert.ok((await page.locator('#spotifyTop iframe').evaluateAll(elements => elements.map(element => element.title))).every(Boolean));
  await enabled(page, 'top');
  await page.locator('#top').click();
  await page.waitForFunction(() => [...document.querySelectorAll('#spotifyTop iframe')].every(frame => frame.src.includes('playlist2')));
  assert.equal(await page.locator('#spotifyTop iframe').count(), 4);
  assert.equal(count, 2);
});

test('Spotify errors allow retry, empty results are not called success, and invalid IDs are never embedded', async t => {
  const page = await createPage(t);
  const responses = [{ status: 503 }, { json: [] }, { json: ['../bad<script>'] }];
  await page.route('**/updateTopPlaylists', route => route.fulfill(responses.shift()));
  await ready(page);
  await page.locator('#top').click();
  await page.waitForFunction(() => document.getElementById('spotifyStatus').dataset.kind === 'error');
  await enabled(page, 'top');
  await page.locator('#top').click();
  await page.waitForFunction(() => document.getElementById('spotifyStatus').textContent.includes('No playlists'));
  assert.equal(await page.locator('#spotifyStatus').getAttribute('data-kind'), 'info');
  await enabled(page, 'top');
  await page.locator('#top').click();
  await page.waitForFunction(() => document.getElementById('spotifyStatus').dataset.kind === 'error');
  assert.equal(await page.locator('#spotifyTop iframe').count(), 0);
});

test('Expired Spotify session disables all mutations and exposes reconnection', async t => {
  const page = await createPage(t);
  await page.route('**/updateTopPlaylists', route => route.fulfill({ status: 401 }));
  await ready(page);
  await page.locator('#top').click();
  await page.locator('#connectSpotify').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#top').isDisabled(), true);
  assert.equal(await page.locator('#lastFmId').isDisabled(), true);
  assert.match(await page.locator('#connectionTitle').textContent(), /expired/);
});

test('Disconnect revokes the session through a protected POST and clears rendered account results', async t => {
  const page = await createPage(t);
  await page.route('**/updateTopPlaylists', route => route.fulfill({ json: ['myplaylist'] }));
  let loggedOut = false;
  await page.route('**/api/logout', route => { assertPost(route); loggedOut = true; return route.fulfill({ status: 204 }); });
  await ready(page);
  await page.locator('#top').click();
  await page.locator('#spotifyTop iframe').waitFor();
  await page.locator('#disconnectSpotify').click();
  await page.locator('#connectSpotify').waitFor({ state: 'visible' });
  assert.equal(loggedOut, true);
  assert.equal(await page.locator('#spotifyTop iframe').count(), 0);
  assert.equal(await page.locator('#resultsSection').isVisible(), false);
});

test('Last.fm validation debounces and supports valid, invalid, empty, and retry states', async t => {
  const page = await createPage(t);
  const requests = [];
  let unavailable = true;
  await page.route('**/verifyLastFmId/**', route => {
    assertPost(route);
    const username = new URL(route.request().url()).pathname.split('/').pop();
    requests.push(username);
    if (username === 'retryme' && unavailable) return route.fulfill({ status: 503 });
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(username !== 'missing') });
  });
  await ready(page);
  await page.locator('#lastFmId').pressSequentially('musiclover', { delay: 15 });
  await enabled(page, 'lastfm');
  assert.deepEqual(requests, ['musiclover']);
  for (const id of ['lastfm', 'forgottenObsessions', 'privateMoodTaxonomy']) assert.equal(await page.locator('#' + id).isEnabled(), true);
  await page.locator('#lastFmId').fill('missing');
  await page.waitForFunction(() => document.getElementById('lastFmId').getAttribute('aria-invalid') === 'true');
  assert.equal(await page.locator('#lastfm').isDisabled(), true);
  await page.locator('#lastFmId').fill('');
  await page.locator('#lastFmId').fill('../bad?#');
  await page.waitForTimeout(450);
  assert.deepEqual(requests, ['musiclover', 'missing']);
  await page.locator('#lastFmId').fill('retryme');
  await page.locator('#retryProfile').waitFor({ state: 'visible' });
  unavailable = false;
  await page.locator('#retryProfile').click();
  await enabled(page, 'lastfm');
});

test('An old validation response cannot enable a changed username', async t => {
  const page = await createPage(t);
  await page.addInitScript(() => { const original = window.fetch; window.fetch = (url, options) => original(url, { ...options, signal: undefined }); });
  let release;
  const delayed = new Promise(resolve => { release = resolve; });
  let started;
  const requestStarted = new Promise(resolve => { started = resolve; });
  await page.route('**/verifyLastFmId/**', async route => {
    if (route.request().url().endsWith('/oldname')) { started(); await delayed; return route.fulfill({ json: true }); }
    return route.fulfill({ contentType: 'application/json', body: 'false' });
  });
  await ready(page);
  await page.locator('#lastFmId').fill('oldname');
  await requestStarted;
  await page.locator('#lastFmId').fill('newname');
  await page.waitForFunction(() => document.getElementById('lastFmId').getAttribute('aria-invalid') === 'true');
  release();
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#lastfm').isDisabled(), true);
});

for (const flow of [
  { button: 'lastfm', endpoint: '/jobs', target: 'yearlyPlaylists', ids: [], message: 'Yearly playlists refreshed' },
  { button: 'forgottenObsessions', endpoint: '/jobs/forgotten-obsessions', target: 'forgottenObsessionsPlaylists', ids: ['forgotten123'], message: 'Forgotten obsessions refreshed' },
  { button: 'privateMoodTaxonomy', endpoint: '/jobs/private-mood-taxonomy', target: 'privateMoodPlaylists', ids: ['anchor1', 'happy1', 'sad1', 'surge1', 'night1', 'frontier1'], message: 'Private mood playlists refreshed' }
]) {
  test(`Last.fm ${flow.button} preserves its REST workflow, progress, and results`, async t => {
    const page = await createPage(t);
    let polls = 0;
    await page.route('**' + flow.endpoint, route => {
      assertPost(route);
      assert.deepEqual(route.request().postDataJSON(), { lastFmLogin: 'musiclover' });
      return route.fulfill({ status: 202, json: { jobId: 'job-123' } });
    });
    await page.route('**/jobs/job-123', route => {
      polls++;
      return route.fulfill({ json: { jobId: 'job-123', state: polls === 1 ? 'RUNNING' : 'COMPLETED', progressPercent: polls === 1 ? 42 : 100, message: polls === 1 ? 'Matching tracks' : flow.message, playlistIds: flow.ids } });
    });
    await ready(page);
    await validUsername(page);
    await page.locator('#' + flow.button).click();
    await page.waitForFunction(() => document.getElementById('lastfmProgressBar').getAttribute('aria-valuenow') === '42');
    assert.equal(await page.locator('#top').isDisabled(), true);
    assert.equal(await page.locator('#lastFmId').isDisabled(), true);
    await page.waitForFunction(() => document.getElementById('lastfmStatus').dataset.kind === 'success');
    assert.equal(await page.locator('#lastfmProgressBar').getAttribute('aria-valuenow'), '100');
    assert.match(await page.locator('#lastfmStatus').textContent(), new RegExp(flow.message));
    assert.equal(await page.locator('#' + flow.target + ' iframe').count(), flow.ids.length);
    await enabled(page, flow.button);
    if (flow.button === 'privateMoodTaxonomy') assert.deepEqual(await page.locator('#privateMoodPlaylists h3').allTextContents(), ['Anchor', 'Happy', 'Sad', 'Surge', 'Night Drift', 'Frontier']);
  });
}

test('Failed jobs report failure without inventing results and permit a new build', async t => {
  const page = await createPage(t);
  await page.route('**/jobs', route => route.fulfill({ json: { jobId: 'failed-job' } }));
  await page.route('**/jobs/failed-job', route => route.fulfill({ json: { state: 'FAILED', progressPercent: 40, message: 'Track matching failed', playlistIds: [] } }));
  await ready(page);
  await validUsername(page);
  await page.locator('#lastfm').click();
  await page.waitForFunction(() => document.getElementById('lastfmStatus').dataset.kind === 'error');
  await enabled(page, 'lastfm');
  assert.equal(await page.locator('#lastfmProgressBar').getAttribute('data-state'), 'FAILED');
  assert.equal(await page.locator('#resultsSection').isVisible(), false);
});

test('Progress transport failure offers retry of the existing job without duplicate mutation', async t => {
  const page = await createPage(t);
  let starts = 0;
  let recovered = false;
  await page.route('**/jobs', route => { starts++; return route.fulfill({ json: { jobId: 'retry-job' } }); });
  await page.route('**/jobs/retry-job', route => recovered
    ? route.fulfill({ json: { state: 'COMPLETED', progressPercent: 100, message: 'Yearly playlists refreshed', playlistIds: [] } })
    : route.fulfill({ json: { unexpected: 'invalid status' } }));
  await ready(page);
  await validUsername(page);
  await page.locator('#lastfm').click();
  await page.locator('#retryJob').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#lastfm').isDisabled(), true);
  recovered = true;
  await page.locator('#retryJob').click();
  await enabled(page, 'lastfm');
  assert.equal(starts, 1);
});

test('Unavailable job polling backs off three times before offering a manual retry', async t => {
  const page = await createPage(t);
  let polls = 0;
  await page.route('**/jobs', route => route.fulfill({ json: { jobId: 'backoff-job' } }));
  await page.route('**/jobs/backoff-job', route => { polls++; return route.fulfill({ status: 503 }); });
  await ready(page);
  await validUsername(page);
  await page.clock.install();
  await page.locator('#lastfm').click();
  await page.waitForFunction(() => document.getElementById('lastfmStatus').textContent.includes('interrupted'));
  for (const delay of [2100, 4100, 8100]) { await page.clock.runFor(delay); await page.waitForTimeout(60); }
  await page.locator('#retryJob').waitFor({ state: 'visible' });
  assert.equal(polls, 4, 'Initial poll and at most three automatic retries');
  assert.equal(await page.locator('#lastfm').isDisabled(), true);
});

test('Authentication failures remain visible and an external Last.fm redirect is never followed', async t => {
  const page = await createPage(t);
  await page.route('**/jobs', route => route.fulfill({ status: 401, headers: { Location: 'https://untrusted.test/auth/lastfm' } }));
  await page.goto(origin + '/?auth=lastfm-invalid-state');
  await enabled(page, 'top');
  assert.match(await page.locator('#authStatus').textContent(), /Last.fm sign-in expired/);
  await validUsername(page);
  await page.locator('#lastfm').click();
  await page.locator('#connectSpotify').waitFor({ state: 'visible' });
  assert.equal(new URL(page.url()).origin, origin);
  assert.equal(await page.locator('#lastfm').isDisabled(), true);
});

test('A Spotify-auth redirect from a failed background job expires the session and exposes reconnection', async t => {
  const page = await createPage(t);
  let authRequests = 0;
  await page.route('**/auth/spotify', route => { authRequests++; return route.fulfill({ contentType: 'text/html', body: '<h1>Spotify sign-in</h1>' }); });
  await page.route('**/jobs', route => route.fulfill({ json: { jobId: 'spotify-expired-job' } }));
  await page.route('**/jobs/spotify-expired-job', route => route.fulfill({ json: { state: 'FAILED', progressPercent: 40, message: 'Spotify authentication required', redirectUrl: '/auth/spotify' } }));
  await ready(page);
  await validUsername(page);
  await page.locator('#lastfm').click();
  await page.locator('#connectSpotify').waitFor({ state: 'visible' });
  assert.match(await page.locator('#connectionTitle').textContent(), /expired/);
  assert.match(await page.locator('#lastfmStatus').textContent(), /connection expired/);
  assert.equal(await page.locator('#lastfmStatus').getAttribute('data-kind'), 'error');
  assert.equal(await page.locator('#disconnectSpotify').isVisible(), false);
  for (const id of ['top', 'lastfm', 'forgottenObsessions', 'privateMoodTaxonomy', 'bandPlaylist']) assert.equal(await page.locator('#' + id).isDisabled(), true);
  assert.equal(await page.evaluate(() => sessionStorage.getItem('replayJob')), null);
  assert.equal(new URL(page.url()).pathname, '/');
  assert.equal(authRequests, 0, 'Reconnection requires an explicit click');
});

for (const redirectUrl of ['https://untrusted.test/auth/spotify', 'https://untrusted.test/auth/lastfm', '/unsupported-auth']) {
  test(`A background job rejects the unsupported authentication redirect ${redirectUrl}`, async t => {
    const page = await createPage(t);
    await page.route('**/jobs', route => route.fulfill({ json: { jobId: 'unsafe-redirect-job' } }));
    await page.route('**/jobs/unsafe-redirect-job', route => route.fulfill({ json: { state: 'FAILED', progressPercent: 40, message: 'Authentication required', redirectUrl } }));
    await ready(page);
    await validUsername(page);
    await page.locator('#lastfm').click();
    await page.waitForFunction(() => document.getElementById('lastfmStatus').textContent.includes('link could not be verified'));
    assert.equal(await page.locator('#lastfmStatus').getAttribute('data-kind'), 'error');
    assert.equal(new URL(page.url()).origin, origin);
    assert.equal(new URL(page.url()).pathname, '/');
    assert.equal(await page.evaluate(() => sessionStorage.getItem('replayJob')), null);
    assert.equal(await page.locator('#lastfm').isEnabled(), true);
  });
}

for (const stage of ['start', 'poll']) {
  test(`Last.fm authentication redirect from ${stage} preserves the recovery flow`, async t => {
    const page = await createPage(t);
    await page.route('**/jobs', route => stage === 'start'
      ? route.fulfill({ status: 401, headers: { Location: '/auth/lastfm?lastFmLogin=musiclover' } })
      : route.fulfill({ json: { jobId: 'auth-job' } }));
    await page.route('**/jobs/auth-job', route => route.fulfill({ json: { state: 'FAILED', progressPercent: 40, message: 'Last.fm authentication required', redirectUrl: '/auth/lastfm?lastFmLogin=musiclover' } }));
    await page.route('**/auth/lastfm?*', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><h1>Last.fm sign-in test</h1>' }));
    await ready(page);
    await validUsername(page);
    await page.locator('#lastfm').click();
    await page.waitForURL('**/auth/lastfm?lastFmLogin=musiclover');
  });
}

test('Band mix validates distinct artists, submits from the keyboard, and reads a text playlist ID', async t => {
  const page = await createPage(t);
  let payload;
  await page.route('**/bandPlaylist', route => { assertPost(route); payload = route.request().postDataJSON(); return route.fulfill({ contentType: 'text/plain', body: 'bandmix123' }); });
  await ready(page);
  await page.locator('#bandNames').fill('Radiohead, radiohead');
  assert.equal(await page.locator('#bandPlaylist').isDisabled(), true);
  await page.locator('#bandNames').fill('Radiohead, Portishead');
  await page.locator('#bandNames').press('Enter');
  await page.locator('#bandPlaylists iframe').waitFor();
  assert.deepEqual(payload, { bands: ['Radiohead', 'Portishead'] });
  assert.equal(await page.locator('#bandStatus').getAttribute('data-kind'), 'success');
  await enabled(page, 'bandNames');
  await page.locator('#bandNames').fill(Array.from({ length: 21 }, (_, i) => 'Artist ' + i).join(','));
  assert.equal(await page.locator('#bandPlaylist').isDisabled(), true);
});

test('A stored running job resumes polling after reload without starting again', async t => {
  const page = await createPage(t);
  await page.addInitScript(() => sessionStorage.setItem('replayJob', JSON.stringify({ id: 'saved-job', kind: 'forgotten' })));
  await page.route('**/jobs/saved-job', route => route.fulfill({ json: { state: 'COMPLETED', progressPercent: 100, message: 'Recovered build', playlistIds: ['recovered1'] } }));
  await ready(page);
  await page.locator('#forgottenObsessionsPlaylists iframe').waitFor();
  assert.equal(await page.evaluate(() => sessionStorage.getItem('replayJob')), null);
});

test('The complete studio is keyboard accessible and fits desktop and narrow mobile screens', async t => {
  const page = await createPage(t);
  await ready(page);
  await page.keyboard.press('Tab');
  assert.equal(await page.locator('.skip-link').evaluate(element => element === document.activeElement), true);
  await page.keyboard.press('Enter');
  assert.equal(new URL(page.url()).hash, '#studio');
  await page.locator('#top').focus();
  assert.notEqual(await page.locator('#top').evaluate(element => getComputedStyle(element).outlineStyle), 'none');
  assert.equal(await page.getByLabel('Your Last.fm username').count(), 1);
  assert.equal(await page.getByLabel('Artists or bands').count(), 1);
  await fs.mkdir(artifacts, { recursive: true });
  await page.goto(origin);
  await enabled(page, 'top');
  await page.screenshot({ path: path.join(artifacts, 'ui-desktop.png'), fullPage: true });
  for (const width of [375, 320]) {
    await page.setViewportSize({ width, height: 812 });
    const sizes = await page.evaluate(() => ({ viewport: document.documentElement.clientWidth, content: document.documentElement.scrollWidth }));
    assert.ok(sizes.content <= sizes.viewport, `No horizontal scrolling at ${width}px: ${JSON.stringify(sizes)}`);
    if (width === 375) await page.screenshot({ path: path.join(artifacts, 'ui-mobile.png'), fullPage: true });
    for (const id of ['top', 'lastfm', 'forgottenObsessions', 'privateMoodTaxonomy', 'bandPlaylist']) assert.equal(await page.locator('#' + id).isVisible(), true);
  }
});
