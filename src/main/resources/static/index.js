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

var lastFmIdValid = false;
var lastFmJobRunning = false;
var lastFmProgressPoll = null;
var verifyRequest;

function buildPlayButtonUrl(id) {
    return "https://open.spotify.com/embed/playlist/" + id;
}

function appendPlayButton(div, playlistId) {
    $('<iframe>', {
        src: buildPlayButtonUrl(playlistId), frameborder: 0, allow: "encrypted-media", allowtransparency: "true"
    }).appendTo('#' + div);
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
    $('#lastFmId').prop('disabled', lastFmJobRunning);
    $('#lastfm').toggleClass('btn-info', enabled);
    $('#lastfm').toggleClass('btn-secondary', !enabled);
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
    }

    if (job.message) {
        setLastfmStatus(job.message);
    }
}

function stopLastfmPolling() {
    if (lastFmProgressPoll) {
        window.clearTimeout(lastFmProgressPoll);
        lastFmProgressPoll = null;
    }
}

function pollLastfmJob(jobId) {
    $.getJSON(URL + '/jobs/' + jobId, function (job) {
        if (job.redirectUrl) {
            stopLastfmPolling();
            lastFmJobRunning = false;
            updateLastfmButtonState();
            window.location.href = job.redirectUrl;
            return;
        }

        renderLastfmProgress(job);
        if (job.state === 'QUEUED' || job.state === 'RUNNING') {
            lastFmProgressPoll = window.setTimeout(function () {
                pollLastfmJob(jobId);
            }, 1000);
            return;
        }

        stopLastfmPolling();
        lastFmJobRunning = false;
        updateLastfmButtonState();
    }).fail(function () {
        stopLastfmPolling();
        lastFmJobRunning = false;
        updateLastfmButtonState();
        setLastfmStatus('Unable to load Last.fm refresh progress right now.');
    });
}

$('#top').on('click', function () {
    $('#top').prop('disabled', true);
    $.ajax({
        type: 'post',
        url: URL + '/updateTopPlaylists',
        success: function (data) {
            $('#spotifyTop iframe').remove();
            $('#top').prop('disabled', false);
            for (var i = 0; i < data.length; i += 1) {
                appendPlayButton('spotifyTop', data[i]);
            }
        },
        error: function () {
            $('#top').prop('disabled', false);
        }
    });
});

$('#lastfm').on('click', function () {
    if (!lastFmIdValid || lastFmJobRunning) {
        return;
    }

    lastFmJobRunning = true;
    updateLastfmButtonState();
    resetLastfmProgress();
    $('#lastfmProgress').removeClass('d-none');
    setLastfmStatus('Starting yearly playlist refresh...');

    $.ajax({
        type: 'post',
        url: URL + '/jobs',
        contentType: 'application/json',
        data: JSON.stringify({lastFmLogin: $('#lastFmId').val().trim()}),
        success: function (data) {
            pollLastfmJob(data.jobId);
        },
        error: function () {
            lastFmJobRunning = false;
            updateLastfmButtonState();
            resetLastfmProgress();
            setLastfmStatus('Unable to start Last.fm refresh right now.');
        }
    });
});

$('#lastFmId').on('input', function () {
    var login = $('#lastFmId').val().trim();
    if (verifyRequest) {
        verifyRequest.abort();
        verifyRequest = null;
    }

    lastFmIdValid = false;
    updateLastfmButtonState();

    if (!login) {
        setLastfmStatus('Enter your Last.fm login to enable yearly refresh.');
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
            $('#bandPlaylists').empty();
            appendPlayButton('bandPlaylists', data);
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
