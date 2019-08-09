$('#top').on('click', function (event) {
    $.post("https://spotify-web-api-demo.herokuapp.com/updateTopPlaylists", function (data, status) {

    }, 'json')
});

$('#lastfm').on('click', function (event) {
    var socket
        = new WebSocket("wss://spotify-web-api-demo.herokuapp.com/socket/"
        + $('#lastFmId').val());
    socket.onmessage = function (message) {
        $("#progressBar").setAttribute("aria-valuenow",
            $.parseJSON(message.data));
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