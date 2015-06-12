# decide-host

Decide is an event-driven state-machine framework for running behavioral experiments with embeddable computers like the Beaglebone Black. This repository contains code for `decide-host`, a process that collates data from multiple devices running `decide`. Among other functions, it tracks what subjects are running experiments on what controller, notifies responsible parties when controllers disconnect unexpectedly, and provides an HTTP API for access to trial data and a simple web page that summarizes the current state of the system.

## Getting started

Detailed instructions for configuring a private local area network for your devices are given in [[docs/deploying]]. For testing purposes, you can run `decide-host` on the computer connected by USB link to the device, or even on the same computer. You will need to have the following installed:

- [leiningen](http://leiningen.org/), 2.0 or later
- [MongoDB](https://mongodb.org/), 3.0 or later
- [zeromq](http://zeromq.org), version 3. Note that on OS X, if you install zeromq using MacPorts, you'll need to make a symbolic link from `/opt/local/lib/libzmq.3.dylib` to `/usr/local/lib` in order for the java bindings to find the library.

You may wish to edit `resources/config/decide-config.edn` to set values for your system.

Run `lein frodo` to start the server. You should be able to connect to <http://localhost:8020> and see a summary of subjects and connected controllers that will update with new events, experiments, and trials. There is also an HTTP API for accessing this information as well as for retrieving trial and event data. See [[docs/decide-http-api]] for details.

You should also probably install `decide-analysis` for some simple python scripts that monitor hopper failures and hourly activity.
