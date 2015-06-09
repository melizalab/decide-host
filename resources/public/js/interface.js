var url = location.href.replace(/http/, "ws") + "ws"
var socket = new WebSocket(url);
socket.onmessage = function(event) {
    var data = JSON.parse(event.data);
    $.each(data, function(i, val) {
        $(val[0]).html(val[1]);
    });
}
