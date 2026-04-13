/*
 * MIT License
 *
 * Copyright (c) 2019 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

const ORIGIN = window.location.origin;
const URL = ORIGIN;
const PRIVATE_MOOD_LABELS = ['Anchor', 'Surge', 'Night Drift', 'Frontier'];

var lastFmIdValid = false;
var lastFmJobRunning = false;
var lastFmProgressPoll = null;
var lastFmRedirectTimer = null;
var lastFmPlaylistConfig = null;
var verifyRequest;

function buildPlayButtonUrl(id) {
    return "https://open.spotify.com/embed/playlist/" + id;
}

function appendPlayButton(div, playlistId, label) {
    var container = document.getElementById(div);
    if (!container) {
        return;
    }
    var wrapper = document.createElement('div');
    wrapper.className = 'playlist-embed';
    if (label) {
        var title = document.createElement('div');
        title.className = 'playlist-embed-title';
        title.textContent = label;
        wrapper.appendChild(title);
    }
    var iframe = document.createElement('iframe');
    iframe.src = buildPlayButtonUrl(playlistId);
    iframe.frameBorder = '0';
    iframe.allow = 'encrypted-media';
    iframe.setAttribute('allowtransparency', 'true');
    wrapper.appendChild(iframe);
    container.appendChild(wrapper);
}

function renderPlaylistEmbeds(div, playlistIds, labels) {
    $('#' + div).empty();
    for (var i = 0; i < playlistIds.length; i += 1) {
        appendPlayButton(div, playlistIds[i], labels && labels[i] ? labels[i] : null);
    }
}

function parseBandNames() {
    return $('#bandNames')
        .val()
        .split(',')
        .map(function (name) {
            return name.trim();
        })
        .filter(function (name) {
            return name.length > 0;
        });
}

function setBandStatus(message) {
    if (message) {
        $('#bandStatus').text(message).removeClass('d-none');
    } else {
        $('#bandStatus').addClass('d-none').text('');
    }
}

function setLastfmStatus(message) {
    $('#lastfmStatus').text(message || '');
}

function getCookie(name) {
    var prefix = name + '=';
    var cookies = document.cookie ? document.cookie.split(';') : [];
    for (var i = 0; i < cookies.length; i += 1) {
        var cookie = cookies[i].trim();
        if (cookie.indexOf(prefix) === 0) {
            return decodeURIComponent(cookie.substring(prefix.length));
        }
    }
    return '';
}

function updateBandButtonState() {
    var bands = parseBandNames();
    var uniqueBands = Array.from(new Set(bands.map(function (name) {
        return name.toLowerCase();
    })));
    var isValid = uniqueBands.length >= 2;
    $('#bandPlaylist').prop('disabled', !isValid);
    if (!isValid && bands.length > 0) {
        setBandStatus('Enter at least two band names to build a playlist.');
    } else if (bands.length === 0) {
        setBandStatus('Enter band names separated by commas to enable the band mix.');
    } else {
        setBandStatus('');
    }
}

function updateLastfmButtonState() {
    var enabled = lastFmIdValid && !lastFmJobRunning;
    $('#lastfm').prop('disabled', !enabled);
    $('#forgottenObsessions').prop('disabled', !enabled);
    $('#privateMoodTaxonomy').prop('disabled', !enabled);
    $('#lastFmId').prop('disabled', lastFmJobRunning);
    $('#lastfm').toggleClass('btn-info', enabled);
    $('#lastfm').toggleClass('btn-secondary', !enabled);
    $('#forgottenObsessions').toggleClass('btn-warning', enabled);
    $('#forgottenObsessions').toggleClass('btn-secondary', !enabled);
    $('#privateMoodTaxonomy').toggleClass('btn-dark', enabled);
    $('#privateMoodTaxonomy').toggleClass('btn-secondary', !enabled);
}

function resetLastfmProgress() {
    $('#lastfmProgress').addClass('d-none');
    $('#lastfmProgressBar')
        .removeClass('bg-danger bg-success')
        .addClass('progress-bar-striped progress-bar-animated')
        .css('width', '0%')
        .attr('aria-valuenow', 0)
        .text('0%');
}

function renderLastfmProgress(job) {
    var progress = Math.max(0, Math.min(100, job.progressPercent || 0));
    $('#lastfmProgress').removeClass('d-none');
    $('#lastfmProgressBar')
        .css('width', progress + '%')
        .attr('aria-valuenow', progress)
        .text(progress + '%');

    if (job.state === 'FAILED') {
        $('#lastfmProgressBar')
            .removeClass('progress-bar-striped progress-bar-animated bg-success')
            .addClass('bg-danger');
    } else if (job.state === 'COMPLETED') {
        $('#lastfmProgressBar')
            .removeClass('progress-bar-striped progress-bar-animated bg-danger')
            .addClass('bg-success');
    } else {
        $('#lastfmProgressBar')
            .removeClass('bg-danger bg-success')
            .addClass('progress-bar-striped progress-bar-animated');
    }

    if (job.message) {
        setLastfmStatus(job.message);
    }
}

function currentLastfmProgress() {
    var current = parseInt($('#lastfmProgressBar').attr('aria-valuenow'), 10);
    if (Number.isNaN(current)) {
        return 0;
    }
    return current;
}

function redirectToLastfmAuth(url) {
    if (lastFmRedirectTimer) {
        window.clearTimeout(lastFmRedirectTimer);
    }
    lastFmRedirectTimer = window.setTimeout(function () {
        window.location.href = url;
    }, 500);
}

function verifyLastfmLogin(login) {
    if (verifyRequest) {
        verifyRequest.abort();
        verifyRequest = null;
    }

    lastFmIdValid = false;
    updateLastfmButtonState();

    if (!login) {
        setLastfmStatus('Enter your Last.fm login to enable Last.fm tools.');
        return;
    }

    setLastfmStatus('Checking Last.fm login...');
    verifyRequest = $.ajax({
        type: 'post',
        url: URL + '/verifyLastFmId/' + encodeURIComponent(login),
        dataType: 'json',
        success: function (data) {
            lastFmIdValid = data === true;
            if (lastFmIdValid) {
                setLastfmStatus('');
            } else {
                setLastfmStatus('Last.fm login not found.');
            }
            updateLastfmButtonState();
        },
        error: function (xhr) {
            if (xhr.statusText === 'abort') {
                return;
            }
            setLastfmStatus('Unable to verify Last.fm login right now.');
            updateLastfmButtonState();
        }
    });
}

function restoreLastfmLogin() {
    var savedLogin = getCookie('lastFmLogin');
    var input = document.getElementById('lastFmId');
    if (!savedLogin || !input || input.value.trim()) {
        return;
    }

    input.value = savedLogin;
    input.dispatchEvent(new Event('input', {bubbles: true}));
}

function stopLastfmPolling() {
    if (lastFmProgressPoll) {
        window.clearTimeout(lastFmProgressPoll);
        lastFmProgressPoll = null;
    }
}

function pollLastfmJob(jobId) {
    $.getJSON(URL + '/jobs/' + jobId, function (job) {
        renderLastfmProgress(job);
        if (job.redirectUrl) {
            stopLastfmPolling();
            lastFmJobRunning = false;
            updateLastfmButtonState();
            setLastfmStatus((job.message || 'Authentication required') + '. Redirecting...');
            redirectToLastfmAuth(job.redirectUrl);
            return;
        }
        if (job.state === 'QUEUED' || job.state === 'RUNNING') {
            lastFmProgressPoll = window.setTimeout(function () {
                pollLastfmJob(jobId);
            }, 1000);
            return;
        }

        stopLastfmPolling();
        lastFmJobRunning = false;
        updateLastfmButtonState();
        if (job.state === 'COMPLETED' && job.playlistIds && job.playlistIds.length > 0 && lastFmPlaylistConfig) {
            renderPlaylistEmbeds(
                lastFmPlaylistConfig.targetId,
                job.playlistIds,
                lastFmPlaylistConfig.labels || null
            );
        }
        if (job.state === 'COMPLETED' || job.state === 'FAILED') {
            lastFmPlaylistConfig = null;
        }
    }).fail(function () {
        stopLastfmPolling();
        lastFmJobRunning = false;
        lastFmPlaylistConfig = null;
        updateLastfmButtonState();
        renderLastfmProgress({state: 'FAILED', progressPercent: currentLastfmProgress()});
        setLastfmStatus('Unable to load Last.fm refresh progress right now.');
    });
}

function startLastfmJob(jobPath, startMessage, failureMessage, playlistConfig) {
    if (!lastFmIdValid || lastFmJobRunning) {
        return;
    }

    lastFmJobRunning = true;
    lastFmPlaylistConfig = playlistConfig || null;
    updateLastfmButtonState();
    resetLastfmProgress();
    $('#lastfmProgress').removeClass('d-none');
    setLastfmStatus(startMessage);

    $.ajax({
        type: 'post',
        url: URL + jobPath,
        contentType: 'application/json',
        data: JSON.stringify({lastFmLogin: $('#lastFmId').val().trim()}),
        success: function (data) {
            if (lastFmPlaylistConfig) {
                $('#' + lastFmPlaylistConfig.targetId).empty();
            }
            pollLastfmJob(data.jobId);
        },
        error: function () {
            lastFmJobRunning = false;
            lastFmPlaylistConfig = null;
            updateLastfmButtonState();
            resetLastfmProgress();
            setLastfmStatus(failureMessage);
        }
    });
}

$('#top').on('click', function () {
    $('#top').prop('disabled', true);
    $.ajax({
        type: 'post',
        url: URL + '/updateTopPlaylists',
        success: function (data) {
            $('#spotifyTop .playlist-embed').remove();
            $('#top').prop('disabled', false);
            renderPlaylistEmbeds('spotifyTop', data, null);
        },
        error: function () {
            $('#top').prop('disabled', false);
        }
    });
});

$('#lastfm').on('click', function () {
    startLastfmJob(
        '/jobs',
        'Starting yearly playlist refresh...',
        'Unable to start Last.fm refresh right now.',
        null
    );
});

$('#forgottenObsessions').on('click', function () {
    startLastfmJob(
        '/jobs/forgotten-obsessions',
        'Scanning Last.fm history for forgotten obsessions...',
        'Unable to start forgotten obsessions scan right now.',
        {targetId: 'forgottenObsessionsPlaylists'}
    );
});

$('#privateMoodTaxonomy').on('click', function () {
    startLastfmJob(
        '/jobs/private-mood-taxonomy',
        'Scanning listening history for private moods...',
        'Unable to start private mood taxonomy right now.',
        {targetId: 'privateMoodPlaylists', labels: PRIVATE_MOOD_LABELS}
    );
});

$('#lastFmId').on('input', function () {
    $('#forgottenObsessionsPlaylists').empty();
    $('#privateMoodPlaylists').empty();
    verifyLastfmLogin($('#lastFmId').val().trim());
});

$('#bandNames').on('input', function () {
    updateBandButtonState();
});

$('#bandPlaylist').on('click', function () {
    var bands = parseBandNames();
    var uniqueBands = Array.from(new Set(bands.map(function (name) {
        return name.toLowerCase();
    })));
    if (uniqueBands.length < 2) {
        setBandStatus('Enter at least two band names to build a playlist.');
        return;
    }

    $('#bandPlaylist').prop('disabled', true);
    setBandStatus('Building your band mix playlist...');
    $.ajax({
        type: 'post',
        url: URL + '/bandPlaylist',
        contentType: 'application/json',
        data: JSON.stringify({bands: bands}),
        success: function (data) {
            renderPlaylistEmbeds('bandPlaylists', [data], null);
            setBandStatus('Playlist ready!');
            updateBandButtonState();
        },
        error: function (xhr) {
            if (xhr.status === 404) {
                setBandStatus('No matching tracks found. Try different band names.');
            } else {
                setBandStatus('Unable to create playlist right now.');
            }
            updateBandButtonState();
        }
    });
});

resetLastfmProgress();
updateLastfmButtonState();
updateBandButtonState();
restoreLastfmLogin();
