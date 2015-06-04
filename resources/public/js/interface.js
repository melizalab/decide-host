var socket = new WebSocket("ws://" + location.host + "/ws");
socket.onmessage = function(event) {
    var data = JSON.parse(event.data);
    $.each(data, function(i, val) {
        $(val[0]).html(val[1]);
    });
}
