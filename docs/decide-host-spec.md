
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
open-peering    = C:OHAI (S:"OHAI-OK" / S:WTF)

OHAI            = "OHAI" protocol hostname
protocol        = STRING                  ; must be 'decide-host@1'
hostname        = STRING

use-peering     = C:PUB (S:ACK / S:DUP / S:RTFM / S:WHO?)
                / C:"HUGZ" S:"HUGZ-OK"
                / S:"HUGZ" C:"HUGZ-OK"

PUB             = "PUB" message-type message-id message-data
message-type    = STRING
message-id      = STRING
message-data    = STRING                  ; json-encoded

ACK             = "ACK" message-id
DUP             = "DUP" message-id

close-peering   = C:"KTHXBAI" / S:"KTHXBAI"

WTF             = "WTF" reason
RTFM            = "RTFM" reason
WHO?            = "WHO?"
```

After connecting for the first time, the client must initiate peering by sending OHAI and its non-qualified hostname. The host must respond with WTF if another connection is associated with the client; RTFM if the client's requested protocol is not supported; and otherwise it must respond with OHAI-OK.

The client sends data to be stored to the host using PUB messages, which comprise the message type, a unique message identifier (to be determined by the client), and the message data, encoded as JSON. Recommended message identifiers are UUIDs or BSON ObjectIDs. The host must respond with an ACK if the message was successfully stored in the database, DUP if the message was a duplicate of a message already stored, WHO? if the socket is not associated with a controller, or RTFM if the message failed to decode or the message type was not supported.
