
This document specifies the protocols used by the *decide* operant control
software to talk to a host computer.

-   Editor: Dan Meliza (dan at meliza.org)
-   Version: 1.0
-   State:  draft
-   URL: <http://meliza.org/specifications/decide-host/>

## Goals and framework

The `decide` operant control software is designed to run on embedded computers (**controllers**) that can directly connect to manipulanda, cues, and reinforcers. The controllers can connect to a server (**host**) that aggregates and stores data from the controllers and provides a secure gateway to outside clients. This document describes the protocol used by the controller and host to communicate.

The current implementation of the protocol uses zeromq because of its robustness and support for transparent reconnection, but may be implemented on any other transport layer that supports bidirectional communication.

## Language

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD",
"SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be
interpreted as described in [RFC 2119](http://tools.ietf.org/html/rfc2119).

## Messages

The following exchanges are allowed between the host (S) and the controller (C).

```abnf
dhc-protocol    = open-peering *use-peering [ close-peering ]
open-peering    = C:OHAI (S:"OHAI-OK" / error)

OHAI            = "OHAI" protocol hostname
protocol        = STRING                  ; must be 'decide-host@1'
hostname        = STRING

use-peering     = C:PUB (S:ACK / S:DUP / error)
                / C:"HUGZ" S:"HUGZ-OK"
                / S:"HUGZ" C:"HUGZ-OK"

PUB             = "PUB" message-type message-id message-data
message-type    = STRING
message-id      = STRING
message-data    = STRING                  ; json-encoded

ACK             = "ACK" message-id
DUP             = "DUP" message-id

close-peering   = C:"KTHXBAI" / S:"KTHXBAI"

error           = (S:WTF / S:RTFM)
WTF             = "WTF" reason
RTFM            = "RTFM" reason
```

After connecting for the first time, the client must initiate peering by sending OHAI and its non-qualified hostname. The host must respond with OHAI-OK if the hostname is not taken by a currently-running client, or WTF if it is.

The client sends data to be stored to the host using PUB messages, which comprise the message type, a unique message identifier (to be determined by the client), and the message data, encoded as JSON.



The host must also provide a second endpoint for connections from external
clients, which may include other processes running on the host. Clients
connected to the external endpoint may send REQ messages, which will be routed.
The host must forward PUB messages from internal clients to external clients.

Normal operation for a controller connected to a host is to forward all PUB
messages for logging on the host. If controller dies, host should notify a
human. If host dies, controller needs to start saving log messages.
