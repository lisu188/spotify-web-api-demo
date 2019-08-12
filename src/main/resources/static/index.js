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

$('#top').on('click', function (event) {
    $.post("https://spotify-web-api-demo.herokuapp.com/updateTopPlaylists", function (data, status) {

    }, 'json')
});

$('#lastfm').on('click', function (event) {
    var socket
        = new WebSocket("wss://spotify-web-api-demo.herokuapp.com/socket/"
        + $('#lastFmId').val());
    socket.onopen = function (ev) {
        $("#progress").show()
    };
    socket.onmessage = function (message) {
        $("#progressBar")[0].style.width =
            $.parseJSON(message.data) + '%';
    };
    socket.onclose = function (ev) {
        $("#progress").hide()
    };
    socket.onerror = function (ev) {
        console.log(ev)
    }
});


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

var verifyRequest;

$('#lastFmId').on('input', function (event) {
    if (verifyRequest) {
        verifyRequest.abort()
    }
    disable();
    verifyRequest = $.post("https://spotify-web-api-demo.herokuapp.com/verifyLastFmId/" + $('#lastFmId').val(),
        function (data, status) {
            if ($.parseJSON(data)) {
                enable()
            } else {
                disable()
            }
        }, 'json')
});