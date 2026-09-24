/* Replay playlist studio. MIT License, Copyright (c) 2019 Andrzej Lis. */
(() => {
    'use strict';
    const byId = (id) => document.getElementById(id);
    const jobKinds = {
        yearly: { button: 'lastfm', path: '/jobs', target: 'yearlyPlaylists', title: 'Yearly playlist' },
        forgotten: { button: 'forgottenObsessions', path: '/jobs/forgotten-obsessions', target: 'forgottenObsessionsPlaylists', title: 'Forgotten obsessions' },
        mood: { button: 'privateMoodTaxonomy', path: '/jobs/private-mood-taxonomy', target: 'privateMoodPlaylists', title: 'Private mood', labels: ['Anchor', 'Happy', 'Sad', 'Surge', 'Night Drift', 'Frontier'] }
    };
    const buildButtons = ['top', 'bandPlaylist', ...Object.values(jobKinds).map((kind) => kind.button)];
    const originalLabels = Object.fromEntries(buildButtons.map((id) => [id, byId(id).innerHTML]));
    let authenticated = false;
    let spotifyConfigured = false;
    let lastFmConfigured = false;
    let sessionBusy = true;
    let actionBusy = false;
    let verifiedUsername = '';
    let verificationVersion = 0;
    let verificationTimer;
    let verificationRequest;
    let activeJob = null;
    let pollTimer;
    let pollFailures = 0;

    function status(id, message, kind = 'info') {
        const element = byId(id);
        element.textContent = message;
        element.dataset.kind = kind;
        element.hidden = !message;
    }

    function bands() {
        const unique = new Map();
        byId('bandNames').value.split(',').map((name) => name.trim()).filter(Boolean).forEach((name) => unique.set(name.toLocaleLowerCase(), name));
        return [...unique.values()];
    }

    function updateControls() {
        const blocked = !authenticated || sessionBusy || actionBusy || !!activeJob;
        byId('top').disabled = blocked;
        byId('bandPlaylist').disabled = blocked || bands().length < 2 || bands().length > 20;
        byId('bandNames').disabled = blocked;
        byId('lastFmId').disabled = blocked || !lastFmConfigured;
        byId('retryProfile').disabled = blocked || !lastFmConfigured;
        Object.values(jobKinds).forEach((kind) => {
            byId(kind.button).disabled = blocked || !lastFmConfigured || !verifiedUsername || verifiedUsername !== byId('lastFmId').value.trim();
        });
        byId('disconnectSpotify').disabled = sessionBusy || actionBusy || !!activeJob;
    }

    function connection(state, title, message) {
        byId('connection').dataset.state = state;
        byId('connection').setAttribute('aria-busy', String(sessionBusy));
        byId('connectionTitle').textContent = title;
        byId('connectionMessage').textContent = message;
        byId('connectSpotify').hidden = authenticated || !spotifyConfigured || sessionBusy;
        byId('disconnectSpotify').hidden = !authenticated;
        byId('retrySession').hidden = !['error', 'unconfigured'].includes(state);
        updateControls();
    }

    function expireSession() {
        authenticated = false;
        verifiedUsername = '';
        verificationVersion++;
        verificationRequest?.abort();
        clearTimeout(verificationTimer);
        clearTimeout(pollTimer);
        clearSavedJob();
        if (activeJob) {
            busyButton(jobKinds[activeJob.kind].button, false);
            status('lastfmStatus', 'Your Spotify connection expired. Connect again to check your playlists.', 'error');
        }
        activeJob = null;
        connection('disconnected', 'Your Spotify connection expired', 'Connect Spotify again to continue. Your existing playlists stay in your library.');
    }

    function sameOriginAuthUrl(value, path) {
        if (!value) return null;
        try {
            const url = new URL(value, window.location.origin);
            return url.origin === window.location.origin && url.pathname === path ? url.href : null;
        } catch { return null; }
    }

    async function request(path, { method = 'GET', body, signal, allowLastFmAuth = false, plainText = false } = {}) {
        const controller = new AbortController();
        const onAbort = () => controller.abort();
        signal?.addEventListener('abort', onAbort, { once: true });
        if (signal?.aborted) controller.abort();
        const timeout = setTimeout(() => controller.abort(), method === 'POST' ? 180000 : 20000);
        try {
            const response = await fetch(path, {
                method, credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
                headers: { Accept: 'application/json', ...(method === 'POST' ? { 'X-Requested-With': 'XMLHttpRequest' } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
                ...(body ? { body: JSON.stringify(body) } : {})
            });
            const authUrl = allowLastFmAuth && sameOriginAuthUrl(response.headers.get('Location'), '/auth/lastfm');
            if (authUrl && !response.ok) throw Object.assign(new Error('Connect Last.fm to continue.'), { authUrl });
            if (response.status === 401 || response.status === 403 || response.redirected) {
                expireSession();
                throw new Error('Your connection needs refreshing. Connect Spotify again to continue.');
            }
            if (!response.ok) {
                const messages = { 400: 'Please check your entries and try again.', 404: 'No matching result was found. Check your entries and try again.', 429: 'Too many requests. Please give it a moment before trying again.' };
                throw Object.assign(new Error(messages[response.status] || 'The service is unavailable right now. Please try again shortly.'), { status: response.status, retryable: response.status === 429 || response.status >= 500 });
            }
            if (response.status === 204) return null;
            if (plainText && !(response.headers.get('Content-Type') || '').includes('application/json')) return await response.text();
            if (!(response.headers.get('Content-Type') || '').includes('application/json')) throw new Error('The server returned an unexpected response. Please try again.');
            return await response.json();
        } catch (error) {
            if (error.name === 'AbortError') throw Object.assign(new Error(method === 'POST' ? 'The response took too long. Check your Spotify library before trying again; the request may still be running.' : 'The service took too long to respond. Please try again.'), { retryable: true });
            if (error instanceof TypeError) throw Object.assign(new Error('Could not reach the server. Check your connection and try again.'), { retryable: true });
            if (error instanceof SyntaxError) throw new Error('The server response could not be read. Please try again.');
            throw error;
        } finally {
            clearTimeout(timeout);
            signal?.removeEventListener('abort', onAbort);
        }
    }

    async function checkSession() {
        if (actionBusy || activeJob) return;
        sessionBusy = true;
        byId('retrySession').hidden = true;
        connection('loading', 'Checking your Spotify connection…', 'Your playlist studio will be ready in a moment.');
        try {
            const session = await request('/api/session');
            if (!session || typeof session.authenticated !== 'boolean' || typeof session.spotifyConfigured !== 'boolean' || typeof session.lastFmConfigured !== 'boolean') throw new Error('Could not read your connection status. Please check again.');
            authenticated = session.authenticated && session.spotifyConfigured;
            spotifyConfigured = session.spotifyConfigured;
            lastFmConfigured = session.lastFmConfigured;
            sessionBusy = false;
            if (!spotifyConfigured) connection('unconfigured', 'Spotify is not available yet', 'This instance needs its Spotify connection set up before you can build playlists.');
            else if (authenticated) connection('connected', 'Spotify connected', 'Choose a source below. Playlists are created only when you press a build button.');
            else connection('disconnected', 'Your listening starts here', 'Connect Spotify to build and save playlists in your library.');
            if (!lastFmConfigured) status('usernameHint', 'Last.fm is not available on this instance yet. Spotify favorites and band mixes are still available.');
            if (authenticated && lastFmConfigured && byId('lastFmId').value.trim()) verifyUsername();
            if (authenticated) restoreJob();
        } catch (error) {
            authenticated = false;
            sessionBusy = false;
            connection('error', 'Could not check your connection', error.message);
        }
    }

    byId('retrySession').addEventListener('click', checkSession);
    byId('disconnectSpotify').addEventListener('click', async () => {
        if (actionBusy || activeJob || sessionBusy) return;
        actionBusy = true;
        updateControls();
        try {
            await request('/api/logout', { method: 'POST' });
            authenticated = false;
            verifiedUsername = '';
            verificationVersion++;
            verificationRequest?.abort();
            clearTimeout(verificationTimer);
            clearSavedJob();
            byId('lastFmId').value = '';
            byId('resultsSection').hidden = true;
            document.querySelectorAll('.results-grid').forEach((element) => element.replaceChildren());
            ['spotifyStatus', 'bandStatus', 'lastfmStatus', 'authStatus'].forEach((id) => status(id, ''));
            status('usernameHint', 'Use a profile with a public listening history.');
            connection('disconnected', 'Spotify disconnected', 'Your playlists are still in your library. Connect again whenever you are ready.');
        } catch (error) { status('authStatus', error.message, 'error'); }
        finally { actionBusy = false; updateControls(); }
    });

    function busyButton(id, busy) {
        byId(id).setAttribute('aria-busy', String(busy));
        byId(id).innerHTML = busy ? 'Building your playlist…' : originalLabels[id];
    }

    function renderPlaylists(target, ids, title, labels = []) {
        if (!Array.isArray(ids) || !ids.every((id) => typeof id === 'string' && /^[a-zA-Z0-9]{1,100}$/.test(id))) throw new Error('The playlist response could not be read. Check your library before trying again.');
        const fragment = document.createDocumentFragment();
        [...new Set(ids)].forEach((id, index) => {
            const name = labels.length === ids.length ? labels[index] : title + (ids.length > 1 ? ' ' + (index + 1) : '');
            const card = document.createElement('article');
            card.className = 'result-card';
            const heading = document.createElement('h3');
            heading.className = 'playlist-embed-title';
            heading.textContent = name;
            const link = document.createElement('a');
            link.href = 'https://open.spotify.com/playlist/' + encodeURIComponent(id);
            link.textContent = 'Open in Spotify ↗';
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            const frame = document.createElement('iframe');
            frame.src = 'https://open.spotify.com/embed/playlist/' + encodeURIComponent(id) + '?theme=0';
            frame.title = name + ' playlist on Spotify';
            frame.loading = 'lazy';
            frame.allow = 'autoplay; clipboard-write; encrypted-media; fullscreen; picture-in-picture';
            frame.referrerPolicy = 'strict-origin-when-cross-origin';
            card.append(heading, link, frame);
            fragment.append(card);
        });
        byId(target).replaceChildren(fragment);
        byId('resultsSection').hidden = !document.querySelector('.results-grid iframe');
    }

    async function buildImmediate(button, path, target, statusId, title, body, labels) {
        if (byId(button).disabled) return;
        actionBusy = true;
        busyButton(button, true);
        updateControls();
        status(statusId, 'Gathering your tracks and updating Spotify. This can take a little while.');
        try {
            const result = await request(path, { method: 'POST', body, plainText: button === 'bandPlaylist' });
            const ids = button === 'bandPlaylist' ? [result] : result;
            renderPlaylists(target, ids, title, labels);
            status(statusId, ids.length ? 'Your playlists are ready. Find them below or in your Spotify library.' : 'No playlists were created. Listen to more music on Spotify, then try again.', ids.length ? 'success' : 'info');
            if (ids.length) byId('results-heading').focus({ preventScroll: true });
        } catch (error) { status(statusId, error.message, 'error'); }
        finally { actionBusy = false; busyButton(button, false); updateControls(); }
    }

    byId('top').addEventListener('click', () => buildImmediate('top', '/updateTopPlaylists', 'spotifyTop', 'spotifyStatus', 'Favorites playlist', undefined, ['Short Term', 'Mid Term', 'Long Term', 'Mixed Term']));
    byId('bandForm').addEventListener('submit', (event) => {
        event.preventDefault();
        buildImmediate('bandPlaylist', '/bandPlaylist', 'bandPlaylists', 'bandStatus', 'Band mix', { bands: bands() });
    });
    byId('bandNames').addEventListener('input', () => {
        const count = bands().length;
        status('bandHint', count > 20 ? 'Choose up to 20 different artists or bands.' : 'Enter 2–20 different names, separated by commas.', count > 20 ? 'error' : 'info');
        updateControls();
    });

    function verifyUsername() {
        clearTimeout(verificationTimer);
        verificationRequest?.abort();
        const version = ++verificationVersion;
        const username = byId('lastFmId').value.trim();
        verifiedUsername = '';
        byId('retryProfile').hidden = true;
        updateControls();
        if (!authenticated || !lastFmConfigured) return;
        if (!username) { status('usernameHint', 'Use a profile with a public listening history.'); byId('lastFmId').setAttribute('aria-invalid', 'false'); return; }
        if (!/^[a-zA-Z0-9_-]{1,64}$/.test(username)) { status('usernameHint', 'Use only letters, numbers, underscores or hyphens.', 'error'); byId('lastFmId').setAttribute('aria-invalid', 'true'); return; }
        status('usernameHint', 'Checking your listening history…');
        verificationTimer = setTimeout(async () => {
            const controller = new AbortController();
            verificationRequest = controller;
            const timeout = setTimeout(() => controller.abort(), 20000);
            try {
                const valid = await request('/verifyLastFmId/' + encodeURIComponent(username), { method: 'POST', signal: controller.signal });
                if (version !== verificationVersion) return;
                if (typeof valid !== 'boolean') throw new Error('The profile response could not be read. Please try again.');
                verifiedUsername = valid ? username : '';
                status('usernameHint', valid ? 'Listening history found. Choose a playlist below.' : 'No listening history found. Check the username or try a public profile.', valid ? 'success' : 'error');
                byId('lastFmId').setAttribute('aria-invalid', String(!valid));
            } catch (error) {
                if (version !== verificationVersion) return;
                status('usernameHint', error.message, 'error');
                byId('retryProfile').hidden = false;
            } finally {
                clearTimeout(timeout);
                if (version === verificationVersion) { verificationRequest = undefined; updateControls(); }
            }
        }, 400);
    }
    byId('lastFmId').addEventListener('input', verifyUsername);
    byId('retryProfile').addEventListener('click', verifyUsername);

    function clearSavedJob() { try { sessionStorage.removeItem('replayJob'); } catch { /* Storage may be blocked. */ } }
    function saveJob() { try { sessionStorage.setItem('replayJob', JSON.stringify(activeJob)); } catch { /* The current job still works without storage. */ } }
    function restoreJob() {
        try {
            const saved = JSON.parse(sessionStorage.getItem('replayJob'));
            if (!saved || !Object.hasOwn(jobKinds, saved.kind) || !/^[a-zA-Z0-9-]{1,100}$/.test(saved.id)) return;
            activeJob = { id: saved.id, kind: saved.kind };
            updateControls();
            status('lastfmStatus', 'Checking the playlist build from your previous visit…');
            pollJob();
        } catch { clearSavedJob(); }
    }

    function renderProgress(job) {
        if (!Number.isFinite(job.progressPercent)) throw new Error('The job progress could not be read.');
        const percentage = Math.round(Math.max(0, Math.min(100, job.progressPercent)));
        byId('lastfmProgress').hidden = false;
        byId('lastfmProgressBar').setAttribute('aria-valuenow', String(percentage));
        byId('lastfmProgressBar').dataset.state = job.state;
        byId('lastfmProgressBar').firstElementChild.style.width = percentage + '%';
        byId('progressValue').textContent = percentage + '%';
        byId('progressLabel').textContent = job.state === 'QUEUED' ? 'Waiting to start…' : job.state === 'COMPLETED' ? 'Playlist build complete' : job.state === 'FAILED' ? 'Playlist build stopped' : 'Building your soundtracks…';
    }

    function finishJob() {
        if (activeJob) busyButton(jobKinds[activeJob.kind].button, false);
        activeJob = null;
        clearSavedJob();
        byId('retryJob').hidden = true;
        updateControls();
    }

    function redirectToLastFm(url) {
        const safeUrl = sameOriginAuthUrl(url, '/auth/lastfm');
        if (!safeUrl) throw new Error('The Last.fm connection link could not be verified. Please reconnect from the studio.');
        status('lastfmStatus', 'Last.fm connection required. Redirecting to connect your account; choose your playlist again when you return.');
        window.setTimeout(() => window.location.assign(safeUrl), 500);
    }

    async function pollJob() {
        if (!activeJob) return;
        const currentJob = activeJob;
        byId('retryJob').hidden = true;
        try {
            const job = await request('/jobs/' + encodeURIComponent(currentJob.id));
            if (activeJob !== currentJob) return;
            if (!job || !['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'].includes(job.state)) throw new Error('The build status could not be read.');
            renderProgress(job);
            status('lastfmStatus', typeof job.message === 'string' ? job.message : 'Building your playlists…', job.state === 'FAILED' ? 'error' : job.state === 'COMPLETED' ? 'success' : 'info');
            pollFailures = 0;
            if (job.redirectUrl) {
                const spotifyAuthUrl = sameOriginAuthUrl(job.redirectUrl, '/auth/spotify');
                const lastFmAuthUrl = sameOriginAuthUrl(job.redirectUrl, '/auth/lastfm');
                if (spotifyAuthUrl) {
                    expireSession();
                } else if (lastFmAuthUrl) {
                    finishJob();
                    redirectToLastFm(lastFmAuthUrl);
                } else {
                    finishJob();
                    status('lastfmStatus', 'The authentication link could not be verified. Please try your playlist again to reconnect.', 'error');
                }
            } else if (job.state === 'QUEUED' || job.state === 'RUNNING') {
                pollTimer = setTimeout(pollJob, 1500);
            } else {
                if (job.state === 'COMPLETED') {
                    const kind = jobKinds[currentJob.kind];
                    renderPlaylists(kind.target, job.playlistIds || [], kind.title, kind.labels);
                    if (!job.playlistIds?.length && currentJob.kind === 'yearly') status('lastfmStatus', (job.message || 'Yearly playlist build completed.') + ' Find any updated playlists in your Spotify library.', 'success');
                }
                finishJob();
            }
        } catch (error) {
            if (activeJob !== currentJob) return;
            if (error.status === 404) {
                finishJob();
                status('lastfmStatus', 'This build is no longer available. Check your Spotify library before starting another build.', 'error');
                return;
            }
            if (error.retryable && ++pollFailures <= 3) {
                status('lastfmStatus', 'The progress connection was interrupted. Checking again shortly; your build may still be running.');
                pollTimer = setTimeout(pollJob, Math.min(1000 * (2 ** pollFailures), 8000));
            } else {
                status('lastfmStatus', error.message + ' Your build may still be running. Check progress again before starting another build.', 'error');
                byId('retryJob').hidden = false;
            }
        }
    }

    Object.entries(jobKinds).forEach(([name, kind]) => {
        byId(kind.button).addEventListener('click', async () => {
            if (byId(kind.button).disabled) return;
            actionBusy = true;
            busyButton(kind.button, true);
            updateControls();
            byId('lastfmProgress').hidden = true;
            status('lastfmStatus', 'Starting your playlist build…');
            try {
                const job = await request(kind.path, { method: 'POST', body: { lastFmLogin: verifiedUsername }, allowLastFmAuth: true });
                if (!job || typeof job.jobId !== 'string' || !/^[a-zA-Z0-9-]{1,100}$/.test(job.jobId)) throw new Error('The build response could not be read. Check your library before trying again.');
                activeJob = { id: job.jobId, kind: name };
                pollFailures = 0;
                saveJob();
                pollJob();
            } catch (error) {
                if (error.authUrl) redirectToLastFm(error.authUrl);
                else status('lastfmStatus', error.message, 'error');
                busyButton(kind.button, false);
            } finally { actionBusy = false; updateControls(); }
        });
    });
    byId('retryJob').addEventListener('click', () => { pollFailures = 0; clearTimeout(pollTimer); pollJob(); });
    window.addEventListener('pagehide', () => { clearTimeout(pollTimer); clearTimeout(verificationTimer); verificationRequest?.abort(); });

    const authErrors = {
        configuration: 'Spotify is not configured on this instance yet. Please try again after its connection is set up.',
        'invalid-state': 'Your Spotify sign-in expired or could not be verified. Connect Spotify to start again.',
        denied: 'Spotify access was not granted. Connect when you are ready to build playlists.',
        failed: 'We could not finish connecting to Spotify. Please connect again.',
        'lastfm-failed': 'We could not finish connecting to Last.fm. Please try your playlist again to reconnect.',
        'lastfm-invalid-state': 'Your Last.fm sign-in expired or could not be verified. Please try your playlist again to reconnect.'
    };
    const authError = new URLSearchParams(window.location.search).get('auth');
    if (Object.hasOwn(authErrors, authError)) status('authStatus', authErrors[authError], 'error');
    try {
        const savedLogin = document.cookie.split(';').map((part) => part.trim()).find((part) => part.startsWith('lastFmLogin='));
        if (savedLogin) byId('lastFmId').value = decodeURIComponent(savedLogin.slice('lastFmLogin='.length)).slice(0, 64);
    } catch { /* A malformed convenience cookie must not prevent connecting. */ }
    updateControls();
    checkSession();
})();
