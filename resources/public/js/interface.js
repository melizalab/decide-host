var socket = new WebSocket("ws://" + location.host + "/ws");
socket.onmessage = function(event) {
    $("#console").html(event.data);
}
