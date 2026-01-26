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

// Dynamically determine the HTTP origin
const ORIGIN = window.location.origin;

const URL = ORIGIN;

function buildPlayButtonUrl(id) {
    return "https://open.spotify.com/embed/playlist/" + id
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

$('#top').on('click', function (event) {
    $('#top').prop('disabled', true)
    $.ajax({
        type: "post", url: URL + "/updateTopPlaylists", success: function (data, text) {
            $('#top').prop('disabled', false);
            for (var playlistId of data) {
                appendPlayButton('spotifyTop', playlistId)
            }
        }, error: function (request, status, error) {
            $('#top').prop('disabled', false)
        }
    });
});

$('#lastfm').on('click', function (event) {
    if (isLastfmSkipped()) {
        return;
    }
    $('#lastfm').prop('disabled', true);
    $('#lastFmId').prop('disabled', true);
    $.ajax({
        type: 'post',
        url: URL + '/jobs',
        contentType: 'application/json',
        data: JSON.stringify({lastFmLogin: $('#lastFmId').val()}),
        complete: function (xhr) {
            $('#lastfm').prop('disabled', false);
            $('#lastFmId').prop('disabled', false);
            if (xhr.status === 302 && xhr.getResponseHeader('Location')) {
                window.location.href = xhr.getResponseHeader('Location');
            }
        }
    });
});

const LASTFM_SKIP_KEY = 'lastfmSkipped';
var lastFmIdValid = false;

function isLastfmSkipped() {
    return window.localStorage.getItem(LASTFM_SKIP_KEY) === 'true';
}

function setLastfmSkipped(skipped) {
    if (skipped) {
        window.localStorage.setItem(LASTFM_SKIP_KEY, 'true');
    } else {
        window.localStorage.removeItem(LASTFM_SKIP_KEY);
    }
}

function enable() {
    $('#lastfm').prop('disabled', false);
    $('#lastfm').removeClass('btn-secondary');
    $('#lastfm').addClass('btn-info');
}

function disable() {
    $('#lastfm').prop('disabled', true);
    $('#lastfm').removeClass('btn-info');
    $('#lastfm').addClass('btn-secondary');
}

function applyLastfmState() {
    if (isLastfmSkipped()) {
        $('#lastFmId').prop('disabled', true);
        disable();
        $('#skipLastfm').addClass('d-none');
        $('#enableLastfm').removeClass('d-none');
        $('#lastfmStatus')
            .text('Last.fm features are disabled. Enable Last.fm to refresh yearly playlists.')
            .removeClass('d-none');
        return;
    }

    $('#lastFmId').prop('disabled', false);
    $('#skipLastfm').removeClass('d-none');
    $('#enableLastfm').addClass('d-none');
    $('#lastfmStatus').addClass('d-none').text('');
    if (lastFmIdValid) {
        enable();
    } else {
        disable();
    }
}

var verifyRequest;

$('#lastFmId').on('input', function (event) {
    if (isLastfmSkipped()) {
        return;
    }
    if (verifyRequest) {
        verifyRequest.abort()
    }
    lastFmIdValid = false;
    disable();
    verifyRequest = $.post(URL + "/verifyLastFmId/" + $('#lastFmId').val(), function (data, status) {
        lastFmIdValid = $.parseJSON(data);
        applyLastfmState();
    }, 'json')
});

$('#skipLastfm').on('click', function () {
    setLastfmSkipped(true);
    applyLastfmState();
});

$('#enableLastfm').on('click', function () {
    setLastfmSkipped(false);
    applyLastfmState();
    $('#lastFmId').trigger('input');
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

applyLastfmState();
updateBandButtonState();
